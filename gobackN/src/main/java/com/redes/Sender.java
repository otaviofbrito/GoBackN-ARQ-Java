/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.redes;

/**
 * @author flavio
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

    private final int portaLocalEnvio = 2000;
    private final int portaDestino = 2001;
    private final int portaLocalRecebimento = 2003;
    private static String addr = "localhost";
    Semaphore sem;
    private final String funcao;

    private static ConcurrentHashMap<Integer, int[]> sendBuffer = new ConcurrentHashMap<>(); // avoid race condition
    private static int sendBase = 0;
    private static int nextSeqNum = 0;
    private static long timeout = 100;;
    private static Timer timer;

    public Sender(Semaphore sem, String funcao) {
        super(funcao);
        this.sem = sem;
        this.funcao = funcao;
    }

    public String getFuncao() {
        return funcao;
    }

    private void enviaPct(int[] dados) {
        // converte int[] para byte[]
        ByteBuffer byteBuffer = ByteBuffer.allocate(dados.length * 4);
        IntBuffer intBuffer = byteBuffer.asIntBuffer();
        intBuffer.put(dados);

        byte[] buffer = byteBuffer.array();

        try {
            if (timer == null) {
                startTimer();
            }
            // System.out.println("Semaforo: " + sem.availablePermits());

            // System.out.println("Semaforo: " + sem.availablePermits());

            InetAddress address = InetAddress.getByName(addr);
            try (DatagramSocket datagramSocket = new DatagramSocket(portaLocalEnvio)) {
                DatagramPacket packet = new DatagramPacket(
                        buffer, buffer.length, address, portaDestino);

                System.out.println("[S]:PCK " + dados[0] + " Enviado.");
                datagramSocket.send(packet);
            }
        } catch (SocketException ex) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void startTimer() {
        stopTimer(); // Parar qualquer timer existente antes de iniciar um novo
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                startTimer();
                System.out.println("timeout ocorreu, reenviando pacotes a partir do: " + sendBase);
                System.out.println(sendBuffer.size() + "/" + sendBase);
                for (int key = sendBase; key < nextSeqNum; key++) {
                    enviaPct(sendBuffer.get(key));
                }
                // sem.release();
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
        switch (getFuncao()) {
            case "envia":
                // variavel onde os dados lidos serao gravados
                int[] dados = new int[350];
                // contador, para gerar pacotes com 1400 Bytes de tamanho
                // como cada int ocupa 4 Bytes, estamos lendo blocos com 350
                // int's por vez.
                int cont = 1; // PCKT = [NSEQ, DADO, DADO, DADO]
                try (FileInputStream fileInput = new FileInputStream("entrada");) {
                    int lido;
                    while ((lido = fileInput.read()) != -1) {
                        dados[0] = nextSeqNum; // Adiciona numero de sequencia ao inicio do pacote
                        dados[cont] = lido;
                        cont++;

                        if (cont == 350) {

                            sem.acquire();
                            // envia pacotes a cada 350 int's lidos.
                            // ou seja, 1400 Bytes.
                            sendBuffer.put(nextSeqNum, dados.clone());
                            enviaPct(dados);
                            nextSeqNum++;
                            cont = 1;
                            System.out.println("ENVIAR" + sendBase);
                        }

                    }

                    // ultimo pacote eh preenchido com
                    // -1 ate o fim, indicando que acabou
                    // o envio dos dados.

                    for (int i = cont; i < 350; i++) {
                        dados[i] = -1;
                    }
                    sem.acquire();
                    sendBuffer.put(nextSeqNum, dados.clone());
                    enviaPct(dados);
                    nextSeqNum++;

                } catch (IOException | InterruptedException e) {
                    System.out.println("Error message: " + e.getMessage());
                }
                break;
            case "ack":
                try (DatagramSocket serverSocket = new DatagramSocket(portaLocalRecebimento);) {

                    byte[] receiveData = new byte[4];
                    int retorno = 0;
                    while (retorno != -2) {
                        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                        serverSocket.receive(receivePacket);
                        byte[] tmp = receivePacket.getData();
                        retorno = ((tmp[0] & 0xff) << 24) + ((tmp[1] & 0xff) << 16) + ((tmp[2] & 0xff) << 8)
                                + ((tmp[3] & 0xff));
                        sendBase = retorno + 1; // CUMULATIVE ACK'S
                        sendBuffer.remove(retorno);
                        System.out.println("[S]:ACK " + retorno + " Recebido.");
                        if (sendBase == nextSeqNum) {
                            stopTimer();
                            // System.out.println("Timer parado");
                            sendBuffer.clear();
                            sem.release();
                        } else {
                            startTimer();
                        }

                    }
                    stopTimer();
                } catch (IOException e) {
                    System.out.println("Excecao: " + e.getMessage());
                }
                break;
            default:
                break;
        }

    }

    public static void main(String[] args) {
        // Usage: java Sender <dest. ip> <window size> <timeout>
        int window_size = 1;
        if (args.length != 0) {
            Sender.addr = args[0];
            window_size = Integer.parseInt(args[1]);
            Sender.timeout = Long.parseLong(args[2]);
        }

        System.out.println("Iniciando transmissão de dados para [" + Sender.addr + "]\nJanela: " + window_size
                + "\nTimeout: " + Sender.timeout + "ms");
        Semaphore sem = new Semaphore(window_size);
        Sender ed1 = new Sender(sem, "envia");
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
