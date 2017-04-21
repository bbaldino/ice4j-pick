package org.jitsi;

import org.ice4j.Transport;
import org.ice4j.ice.*;
import org.jitsi.messages.CandidateMessage;
import org.jitsi.messages.ConnectMessage;
import org.jitsi.messages.Message;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

/**
 * Created by bbaldino on 4/17/17.
 */
public class Client
    extends ClientServerCommon
{
    protected String serverUfrag;
    protected String serverPass;
    protected boolean running = true;

    public Client(String serverAddressStr, int serverPort)
            throws IOException
    {
        signalingSocket = new DatagramSocket();
        System.out.println("Client bound to address " + signalingSocket.getLocalSocketAddress());
        InetAddress serverAddress = InetAddress.getByName(serverAddressStr);
        signalingSocket.connect(serverAddress, serverPort);
    }

    protected void createAgentAndStream()
    {
        iceAgent = new Agent();
        iceAgent.setLoggingLevel(Level.FINE);
        iceAgent.setControlling(true);
        iceAgent.setUseHostHarvester(true);

        iceMediaStream = iceAgent.createMediaStream("stream");
        try
        {
            iceAgent.createComponent(iceMediaStream, Transport.UDP, 10101, 10101, 10200);
        } catch (IOException e)
        {
            e.printStackTrace();
        }

        iceAgent.addStateChangeListener(evt ->
        {
            System.out.println("ICE property change: " + evt.getPropertyName() + " -> " + evt.getNewValue());
            if (evt.getNewValue() == IceProcessingState.COMPLETED)
            {
                System.out.println("ICE completed!  Starting data send");
                startDataLoop();
            }
        });
    }

    Map<Integer, Integer> packetTypesSent = new HashMap<>();
    private Random rand = new Random();
    protected void setPacketType(byte[] data)
    {
        int num = rand.nextInt(100);
        int packetType;
        // 33% audio rtp packet
        if (num < 33)
        {
            packetType = AUDIO_RTP_PACKET_TYPE;
        }
        // 2% audio rtcp packet
        else if (num < 35)
        {
            packetType = AUDIO_RTCP_PACKET_TYPE;
        }
        // 60% chance video rtp packet
        else if (num < 95)
        {
            packetType = VIDEO_RTP_PACKET_TYPE;
        }
        // 2% chance video rtcp packet
        else if (num < 97)
        {
            packetType = VIDEO_RTCP_PACKET_TYPE;
        }
        // 3% chance dtls packet
        else
        {
            packetType = DTLS_PACKET_TYPE;
        }
        packetTypesSent.put(packetType, packetTypesSent.getOrDefault(packetType, 0) + 1);
        ByteBuffer.wrap(data).putInt(packetType);
    }

    protected void startDataLoop()
    {
        new Thread() {
            @Override
            public void run()
            {
                byte[] data = new byte[PACKET_SIZE_BYTES];
                DatagramPacket p = new DatagramPacket(data, PACKET_SIZE_BYTES);
                DatagramSocket s = iceMediaStream.getComponents().get(0).getSocket();
                long startTime = System.nanoTime();
                for (int i = 0; i < NUM_PACKETS_TO_SEND; ++i)
                {
                    setPacketType(data);
                    try
                    {
                        s.send(p);
                    }
                    catch (IOException e)
                    {
                        System.out.println("Error sending data: " + e.toString());
                    }
                }
                long finishTime = System.nanoTime();
                long bytesSent = (long)NUM_PACKETS_TO_SEND * (long)PACKET_SIZE_BYTES;
                System.out.println("Sent " + (NUM_PACKETS_TO_SEND) + " packets in " +
                        (finishTime - startTime) +
                        " nanoseconds at a rate of " +
                        getBitrateMbps(bytesSent, finishTime - startTime) +
                        "mbps");
                System.out.println(packetTypesSent.toString());
            }
        }.start();
    }


    public void startSignalingLoop()
    {
        new Thread() {
            @Override
            public void run()
            {
                createAgentAndStream();
                connect();
                while (running)
                {
                    System.out.println("Client waiting for message");
                    Message message = waitForMessage();
                    handleMessage(message);
                }
            }
        }.start();
    }

    protected void connect()
    {
        System.out.println("Client connecting with ufrag " + iceAgent.getLocalUfrag() + " and pass " + iceAgent.getLocalPassword());
        ConnectMessage connectMessage = new ConnectMessage(iceAgent.getLocalUfrag(), iceAgent.getLocalPassword());
        sendSignalingMessage(connectMessage);
    }

    protected void handleMessage(Message message)
    {
        if (message instanceof ConnectMessage)
        {
            ConnectMessage connectMessage = (ConnectMessage)message;
            serverUfrag = connectMessage.ufrag;
            serverPass = connectMessage.pass;
            iceMediaStream.setRemoteUfrag(serverUfrag);
            iceMediaStream.setRemotePassword(serverPass);
            System.out.println("Got connection response from server with ufrag " +
                    serverUfrag + " and pass " + serverPass + ", sending candidates");

            sendCandidates();
        }
        else if (message instanceof CandidateMessage)
        {
            CandidateMessage candidateMessage = (CandidateMessage)message;
            RemoteCandidate remoteCandidate = candidateMessage.toRemoteCandidate(iceMediaStream.getComponents().get(0));
            System.out.println("Got remote candidate: " + remoteCandidate.toString());
            iceMediaStream.getComponents().get(0).addRemoteCandidate(remoteCandidate);
            if (!iceAgent.isStarted())
            {
                iceAgent.startConnectivityEstablishment();
            }
        }
    }
}
