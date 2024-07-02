package com.redes;

/**
 * @author Flavio
 * @author Otavio Ferreira
 * @author Alicia Garnier
 */

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Receiver extends Thread {

    private final int localReceivingPort = 2001;
    private final int localSendingPort = 2002;
    private final int destinationPort = 2003;

    private static String addr = "localhost";

    private int nextSeqNum = 0;

    /**
     * Send back ACK's for packets received
     * 
     * @param end  end of file flag
     * @param nseq sequence number for the last acknowleged packet
     */
    private void sendAck(boolean end, int nseq) {
        try {
            InetAddress address = InetAddress.getByName(addr);
            try (DatagramSocket datagramSocket = new DatagramSocket(localSendingPort)) {
                int ack = end ? -2 : nseq;
                byte[] sendData = ByteBuffer.allocate(4).putInt(ack).array();

                DatagramPacket packet = new DatagramPacket(
                        sendData, sendData.length, address, destinationPort);

                datagramSocket.send(packet);
            }
        } catch (SocketException ex) {
            Logger.getLogger(Receiver.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Receiver.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public void run() {
        try (DatagramSocket serverSocket = new DatagramSocket(localReceivingPort);) {
            byte[] receiveData = new byte[1400];
            try (FileOutputStream fileOutput = new FileOutputStream("output")) {
                boolean end = false;
                while (!end) {

                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    serverSocket.receive(receivePacket);

                    byte[] tmp = receivePacket.getData();
                    // Get packet sequence number
                    int pcktNum = ((tmp[0] & 0xff) << 24) + ((tmp[1] & 0xff) << 16) + ((tmp[2] & 0xff) << 8)
                            + ((tmp[3] & 0xff));

                    /*
                     * 60% probability of loss
                     * generate a random number in the range [0,1]
                     * if the number falls within [0, 0.6)
                     * it means loss, hence you do not send an ACK
                     * for this packet, and do not write it to the output file.
                     * if the number falls within [0.6, 1.0]
                     * it is assumed to be successfully received.
                     */

                    double rand = Math.random();
                    if (rand >= 0.6) {
                        if (pcktNum == nextSeqNum) {
                            System.out.println("\t\t\t\tPCK " + pcktNum + " Received:[R]");
                            // The first byte belongs to sequence number, so
                            // start reading from the 4th bit
                            for (int i = 4; i < tmp.length; i = i + 4) {
                                int data = ((tmp[i] & 0xff) << 24) + ((tmp[i + 1] & 0xff) << 16)
                                        + ((tmp[i + 2] & 0xff) << 8) + ((tmp[i + 3] & 0xff));

                                if (data == -1) {
                                    end = true;
                                    break;
                                }
                                fileOutput.write(data);
                            }
                            nextSeqNum++;
                            System.out.println("\t\t\t\tACK " + pcktNum + " Sent:[R]");
                            sendAck(end, pcktNum);
                        } else {
                            // Drop packet if not in order
                            // Send back ACK for the last packet received in the correct order
                            System.out.println("\t\t\t\tPCK " + pcktNum + " Dropped:[R]");
                            System.out.println("\t\t\t\tACK " + (nextSeqNum - 1) + " Sent:[R]");
                            sendAck(end, nextSeqNum - 1);
                        }
                    } else {
                        System.out.println("\t\tPCK " + pcktNum + " Lost!");
                    }

                }
            }
        } catch (IOException e) {
            System.out.println("Exception: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Usage: java Receiver <destination IP address>
        if (args.length != 0) {
            Receiver.addr = args[0];
        }
        Receiver rd = new Receiver();
        System.out.println(">Receiver started \n>Waiting for data from [" + Receiver.addr + "] ...\n");
        rd.start();
        try {
            rd.join();
        } catch (InterruptedException ex) {
            Logger.getLogger(Receiver.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
