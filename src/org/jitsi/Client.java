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

        iceAgent.addStateChangeListener(new PropertyChangeListener()
        {
            public void propertyChange(PropertyChangeEvent evt)
            {
                System.out.println("ICE property change: " + evt.getPropertyName() + " -> " + evt.getNewValue());
                if (evt.getNewValue() == IceProcessingState.COMPLETED)
                {
                    System.out.println("ICE completed!  Starting data send");
                    startDataLoop();
                }
            }
        });
    }

    protected void startDataLoop()
    {
        new Thread() {
            @Override
            public void run()
            {
                int packetSize = 1500;
                int ONE_HUNDRED_THOUSAND = 100000;
                int TEN_MILLION = 10000000;
                int numPackets = ONE_HUNDRED_THOUSAND;
                byte[] data = new byte[packetSize];
                DatagramPacket p = new DatagramPacket(data, packetSize);
                long startTime = System.currentTimeMillis();
                for (int i = 0; i < numPackets; ++i)
                {
                    try
                    {
                        iceMediaStream.getComponents().get(0).getSocketWrapper().send(p);

                    } catch (IOException e)
                    {
                        System.out.println("Error sending data: " + e.toString());
                    }
                }
                long finishTime = System.currentTimeMillis();
                System.out.println("Sent " + (numPackets * packetSize) + " bytes in " + (finishTime - startTime) +
                        "ms at a rate of " +
                    (numPackets * packetSize * 8 / 1000) / ((finishTime - startTime) / 1000.0) + "mbps");
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
            if (/*iceMediaStream.getComponents().get(0).getRemoteCandidateCount() == 2 */ !iceAgent.isStarted())
            {
                iceAgent.startConnectivityEstablishment();
            }
        }
    }
}
