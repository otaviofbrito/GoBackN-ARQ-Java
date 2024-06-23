package com.redes;

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

    private final int portaLocalEnvio = 2000;
    private final int portaDestino = 2001;
    private final int portaLocalRecebimento = 2003;
    private static String addr = "localhost";
    Semaphore sem;
    private final String funcao;

    // buffer (enviados não confirmados)
    private static ConcurrentHashMap<Integer, byte[]> sendBuffer = new ConcurrentHashMap<>();

    private static int WINDOW_SIZE = 1;
    private static int sendBase = 0;
    private static int nextSeqNum = 0;
    private static long timeout = 100;
    private static Timer timer;

    public Sender(Semaphore sem, String funcao) {
        super(funcao);
        this.sem = sem;
        this.funcao = funcao;
    }

    public String getFuncao() {
        return funcao;
    }

    /**
     * Cria pacote UDP e envia para o destino.
     * (portaLocalEnvio, IP origem) -> (portaDestino, IP destino).
     * 
     * @param buffer byte array contendo os dados do pacote
     */
    private void enviaPct(byte[] buffer) {
        int pcktNum = ((buffer[0] & 0xff) << 24) + ((buffer[1] & 0xff) << 16) + ((buffer[2] & 0xff) << 8)
                + ((buffer[3] & 0xff));
        try {
            InetAddress address = InetAddress.getByName(addr);
            try (DatagramSocket datagramSocket = new DatagramSocket(portaLocalEnvio)) {
                DatagramPacket packet = new DatagramPacket(
                        buffer, buffer.length, address, portaDestino);
                datagramSocket.send(packet);
                System.out.println("[S]:PCK " + pcktNum + " Enviado.");
            }
        } catch (SocketException ex) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void criaPacote(int[] dados) {
        try {
            sem.acquire();// Para aqui enquanto a janela deslizante não se mover

            // converte int[] para byte[]
            ByteBuffer byteBuffer = ByteBuffer.allocate(dados.length * 4);
            IntBuffer intBuffer = byteBuffer.asIntBuffer();
            intBuffer.put(dados);
            byte[] buffer = byteBuffer.array();

            sendBuffer.put(nextSeqNum, buffer.clone()); // Adiciona pacote ao buffer.
            // Inicia timer para sendBase se não houver.
            if (timer == null)
                startTimer();
            enviaPct(buffer);
            nextSeqNum++;
        } catch (InterruptedException e) {
            Logger.getLogger(Sender.class.getName()).log(Level.SEVERE, null, e);
        }

    }

    /*
     * Inicia timer para sendBase
     * TimerTask: Reenvia pacotes da janela de envio(buffer)
     */
    private void startTimer() {
        stopTimer();
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Timeout
                startTimer();
                System.out.println("\nTimeout! Reenviando pacotes a partir do: " + sendBase);
                for (int key = sendBase; key < nextSeqNum; key++) {
                    enviaPct(sendBuffer.get(key));
                }
                System.out.println();
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

                // Reserva o primeiro byte para o número de sequência.
                int cont = 1; // PCKT = [Nmro. de Seq., DADO, DADO, DADO,...,-1]
                try (FileInputStream fileInput = new FileInputStream("entrada");) {
                    int lido;
                    while ((lido = fileInput.read()) != -1) {
                        dados[0] = nextSeqNum; // Adiciona número de sequência ao inicio do pacote.
                        dados[cont] = lido;
                        cont++;

                        // envia pacotes a cada 350 int's lidos.
                        // ou seja, 1400 Bytes.
                        if (cont == 350) {
                            criaPacote(dados);
                            cont = 1;
                        }
                    }

                    // ultimo pacote eh preenchido com
                    // -1 ate o fim, indicando que acabou
                    // o envio dos dados.
                    for (int i = cont; i < 350; i++) {
                        dados[i] = -1;
                    }
                    criaPacote(dados);

                } catch (IOException e) {
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
                        sendBase = retorno + 1; // ACK acumulativo
                        sendBuffer.remove(retorno);
                        System.out.println("[S]:ACK " + retorno + " Recebido.");
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
                    System.out.println("Excecao: " + e.getMessage());
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
        System.out.println(">Iniciando transmissão de dados para [" + Sender.addr + "]\n>Janela: " + Sender.WINDOW_SIZE
                + "\n>Timeout: " + Sender.timeout + "ms\n");
        Semaphore sem = new Semaphore(Sender.WINDOW_SIZE);
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
