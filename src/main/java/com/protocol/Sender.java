package com.protocol;

/**
 * @author Flavio
 * @author Otavio Ferreira
 * @author Alicia Garnier
 */

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class Sender extends Thread {

    private final int localSendingPort = 2000;
    private final int destinationPort = 2001;
    private final int localReceivingPort = 2003;
    private static String addr = "localhost";
    Semaphore sem;
    private final String job;

    // buffer (sent but not ACK'ed)
    private static ConcurrentHashMap<Integer, byte[]> sendBuffer = new ConcurrentHashMap<>();

    private static int WINDOW_SIZE = 1;
    private static int sendBase = 0;
    private static int nextSeqNum = 0;
    private static long timeout = 100;
    private static Timer timer;

    public Sender(Semaphore sem, String job) {
        super(job);
        this.sem = sem;
        this.job = job;
    }

    public String getJob() {
        return job;
    }

    /**
     * Creates a UDP packet and sends it to the destination.
     * (localSendingPort, sourceIP) -> (destinationPort, destinationIP).
     * 
     * @param buffer byte array countaining the packet data
     */
    private void sendPacket(byte[] buffer) {
        int pcktNum = ((buffer[0] & 0xff) << 24) + ((buffer[1] & 0xff) << 16) + ((buffer[2] & 0xff) << 8)
                + ((buffer[3] & 0xff));
        try {
            InetAddress address = InetAddress.getByName(addr);
            try (DatagramSocket datagramSocket = new DatagramSocket(localSendingPort)) {
                DatagramPacket packet = new DatagramPacket(
                        buffer, buffer.length, address, destinationPort);
                datagramSocket.send(packet);
                System.out.println("[S]:PCK " + pcktNum + " Sent.");
            }
        } catch (SocketException ex) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void createPacket(int[] data) {
        try {
            sem.acquire();// Stop here while the sliding window does not move.

            // convert int[] to byte[]
            ByteBuffer byteBuffer = ByteBuffer.allocate(data.length * 4);
            IntBuffer intBuffer = byteBuffer.asIntBuffer();
            intBuffer.put(data);
            byte[] buffer = byteBuffer.array();

            sendBuffer.put(nextSeqNum, buffer.clone()); // Copy packet into buffer.
            nextSeqNum++;

            sendPacket(buffer);
            // Start timer only for sendBase.
            if (timer == null)
                startTimer();
        } catch (InterruptedException e) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, e);
        }

    }

    /*
     * Start a timer
     * TimerTask: Resend packets from the sending window (buffer).
     */
    private void startTimer() {
        stopTimer();
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!sendBuffer.isEmpty()) {
                    // Timeout
                    System.out.println("\nTimeout! Resending packets starting from: " + sendBase);
                    for (int key = sendBase; key < nextSeqNum; key++) {
                        sendPacket(sendBuffer.get(key));
                    }
                    startTimer();
                    System.out.println();
                } else {
                    System.out.println("\n[Timeout too fast!]");
                }
            }
        }, timeout);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    @Override
    public void run() {
        switch (getJob()) {
            case "send":
                // Array where the read data will be stored
                int[] data = new int[350];

                int count = 1; // PCKT = [Seq. Num., DATA, DATA, DATA,...,-1]
                // Counter to generate packets with 1400 Bytes in size
                // Reserve the first position for the sequence number.
                // Since each int occupies 4 bytes, we are reading blocks of 350 ints at a time.

                try (FileInputStream fileInput = new FileInputStream("input.jpg");) {
                    int lido;
                    while ((lido = fileInput.read()) != -1) {
                        data[0] = nextSeqNum; // Add sequence number to the beginning of the packet.
                        data[count] = lido;
                        count++;

                        // Sends packets for every 350 ints read.
                        // = 1400 Bytes.
                        if (count == 350) {
                            createPacket(data);
                            count = 1;
                        }
                    }
                    // The last packet is filled with -1 until the end, indicating the end of data
                    // transmission.
                    for (int i = count; i < 350; i++) {
                        data[i] = -1;
                    }
                    createPacket(data);

                } catch (IOException e) {
                    System.out.println("Error message: " + e.getMessage());
                }
                break;
            case "ack":
                try (DatagramSocket serverSocket = new DatagramSocket(localReceivingPort);) {

                    byte[] receiveData = new byte[4];
                    int recv = 0;
                    while (recv != -2) {
                        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                        serverSocket.receive(receivePacket);
                        byte[] tmp = receivePacket.getData();
                        recv = ((tmp[0] & 0xff) << 24) + ((tmp[1] & 0xff) << 16) + ((tmp[2] & 0xff) << 8)
                                + ((tmp[3] & 0xff));
                        sendBase = recv + 1; // Cumulative ACK's
                        sendBuffer.remove(recv);
                        System.out.println("[S]:ACK " + recv + " Received.");
                        if (sendBase == nextSeqNum) {
                            stopTimer();
                            sendBuffer.clear();
                            sem.release();
                        } else {
                            startTimer();
                        }

                    }
                    stopTimer();
                } catch (IOException e) {
                    System.out.println("Exception: " + e.getMessage());
                }
                break;
            default:
                break;
        }

    }

    public static void main(String[] args) {
        // Usage: java Sender <dest. ip> <window size> <timeout>

        if (args.length != 0) {
            Sender.addr = args[0];
            Sender.WINDOW_SIZE = Integer.parseInt(args[1]);
            Sender.timeout = Long.parseLong(args[2]);
        }
        System.out.println("> Initiating data transmission to [" + Sender.addr + "]\n> Window: " + Sender.WINDOW_SIZE
                + "\n> Timeout: " + Sender.timeout + "ms\n");
        Semaphore sem = new Semaphore(Sender.WINDOW_SIZE);
        Sender ed1 = new Sender(sem, "send");
        Sender ed2 = new Sender(sem, "ack");

        ed2.start();
        ed1.start();

        try {
            ed1.join();
            ed2.join();

        } catch (InterruptedException ex) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
