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

    private final int portaLocalReceber = 2001;
    private final int portaLocalEnviar = 2002;
    private final int portaDestino = 2003;

    private static String addr = "localhost";

    private int nextSeqNum = 0;

    /**
     * Send back ACK's for packets received
     * 
     * @param fim  end of file flag
     * @param nseq sequence number for the last acknowleged packet
     */
    private void enviaAck(boolean fim, int nseq) {
        try {
            InetAddress address = InetAddress.getByName(addr);
            try (DatagramSocket datagramSocket = new DatagramSocket(portaLocalEnviar)) {
                int ack = fim ? -2 : nseq;
                byte[] sendData = ByteBuffer.allocate(4).putInt(ack).array();

                DatagramPacket packet = new DatagramPacket(
                        sendData, sendData.length, address, portaDestino);

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
        try (DatagramSocket serverSocket = new DatagramSocket(portaLocalReceber);) {
            byte[] receiveData = new byte[1400];
            try (FileOutputStream fileOutput = new FileOutputStream("saida")) {
                boolean fim = false;
                while (!fim) {

                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    serverSocket.receive(receivePacket);

                    byte[] tmp = receivePacket.getData();
                    // Get packet sequence number
                    int pcktNum = ((tmp[0] & 0xff) << 24) + ((tmp[1] & 0xff) << 16) + ((tmp[2] & 0xff) << 8)
                            + ((tmp[3] & 0xff));

                    /*
                     * probabilidade de 60% de perder
                     * gero um numero aleatorio contido entre [0,1]
                     * se numero cair no intervalo [0, 0,6)
                     * significa perda, logo, você não envia ACK
                     * para esse pacote, e não escreve ele no arquivo saida.
                     * se o numero cair no intervalo [0,6, 1,0]
                     * assume-se o recebimento com sucesso.
                     */
                    double rand = Math.random();
                    if (rand >= 0.6) {
                        if (pcktNum == nextSeqNum) {
                            System.out.println("\t\t\t\tPCK " + pcktNum + " Recebido:[R]");
                            // The first byte belongs to sequence number, so
                            // start reading from the 4th bit
                            for (int i = 4; i < tmp.length; i = i + 4) {
                                int dados = ((tmp[i] & 0xff) << 24) + ((tmp[i + 1] & 0xff) << 16)
                                        + ((tmp[i + 2] & 0xff) << 8) + ((tmp[i + 3] & 0xff));

                                if (dados == -1) {
                                    fim = true;
                                    break;
                                }
                                fileOutput.write(dados);
                            }
                            nextSeqNum++;
                            System.out.println("\t\t\t\tACK " + pcktNum + " ENVIADO:[R]");
                            enviaAck(fim, pcktNum);
                        } else {
                            // Drop packet if not in order
                            // Send back ACK for the last packet received in the correct order
                            System.out.println("\t\t\t\tPCK " + pcktNum + " Descartado:[R]");
                            System.out.println("\t\t\t\tACK " + (nextSeqNum - 1) + " Enviado:[R]");
                            enviaAck(fim, nextSeqNum - 1);
                        }
                    } else {
                        System.out.println("\t\tPCK " + pcktNum + " Perdido!");
                    }

                }
            }
        } catch (IOException e) {
            System.out.println("Excecao: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Usage: java Receiver <destination IP address>
        if (args.length != 0) {
            Receiver.addr = args[0];
        }
        Receiver rd = new Receiver();
        System.out.println(">Receiver iniciado \n>Aguardando receber dados de [" + Receiver.addr + "] ...\n");
        rd.start();
        try {
            rd.join();
        } catch (InterruptedException ex) {
            Logger.getLogger(Receiver.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
