package org.jitsi;

import org.ice4j.Transport;
import org.ice4j.ice.*;
import org.ice4j.socket.IceSocketWrapper;
import org.jitsi.messages.CandidateMessage;
import org.jitsi.messages.ConnectMessage;
import org.jitsi.messages.Message;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by bbaldino on 4/17/17.
 */
public class Server
    extends ClientServerCommon
{
    public class Peer
    {
        public Peer(SocketAddress address)
        {
            this.address = address;
        }

        SocketAddress address;
        String ufrag;
        String pass;
    }
    protected Map<InetAddress, Peer> peers = new HashMap<InetAddress, Peer>();

    public Server()
            throws IOException
    {
        signalingSocket = new DatagramSocket(52000, InetAddress.getByName("localhost"));
        System.out.println("Server listening at address " + signalingSocket.getLocalSocketAddress());
        iceAgent = new Agent();
        // Server is never controlling
        iceAgent.setControlling(false);
        iceAgent.setUseHostHarvester(true);

        iceMediaStream = iceAgent.createMediaStream("stream");
        Component component = iceAgent.createComponent(iceMediaStream, Transport.UDP, 10000, 10000, 10100);
        iceAgent.addStateChangeListener(evt ->
        {
            System.out.println("ICE property change: " + evt.getPropertyName() + " -> " + evt.getNewValue());
            if (evt.getNewValue() == IceProcessingState.COMPLETED)
            {
                DatagramSocket s = iceMediaStream.getComponents().get(0).getSocket();
                startDataLoop(s);
            }
        });

        for (LocalCandidate candidate : component.getLocalCandidates())
        {
            System.out.println("Got local candidate: " + candidate);
        }
    }

    protected void startDataLoop(DatagramSocket socket)
    {
        new Thread("Server app reader thread") {
            @Override
            public void run()
            {
                byte[] data = new byte[1500];
                DatagramPacket p = new DatagramPacket(data, 1500);
                int numPacketsReceived = 0;
                try
                {
                    socket.setSoTimeout(10);
                    socket.setReceiveBufferSize(106496);
                    System.out.println("Receive socket buffer size is " + socket.getReceiveBufferSize());
                } catch (SocketException e)
                {
                    System.out.println("Error setting socket config " + e.toString());
                }
                long firstPacketTime = -1;
                while (running)
                {
                    try
                    {
                        socket.receive(p);
                        if (firstPacketTime < 0)
                        {
                            firstPacketTime = System.nanoTime();
                        }
                        numPacketsReceived++;
                    }
                    catch (SocketTimeoutException e)
                    {
                        long lastPacketTime = System.nanoTime() - 10000000; // 10ms in nanos
                        System.out.println("Received " + numPacketsReceived + " packets in " +
                                (lastPacketTime - firstPacketTime) + " nanoseconds" + " at a rate of " +
                                getBitrateMbps(numPacketsReceived * PACKET_SIZE_BYTES, lastPacketTime - firstPacketTime) +
                                "mbps");
                        break;
                    }
                    catch (IOException e)
                    {
                        System.out.println("Error sending data: " + e.toString());
                    }

                }
            }
        }.start();
    }

    /**
     * Runs the loop to send and receive signaling messages
     * @throws IOException
     */
    public void startSignaling()
            throws IOException
    {
        final byte[] buf = new byte[1500];
        final DatagramPacket p = new DatagramPacket(buf, 1500);
        signalingThread = new Thread()
        {
            @Override
            public void run()
            {
                while (running)
                {
                    Arrays.fill(buf, (byte)0);
                    try
                    {
                        System.out.println("Server listening for packet");
                        signalingSocket.receive(p);
                    }
                    catch (IOException e)
                    {
                        System.out.println("Error receiving signaling message: " + e.toString());
                        continue;
                    }
                    if (!peers.containsKey(p.getAddress()))
                    {
                        peers.put(p.getAddress(), new Peer(p.getSocketAddress()));
                        System.out.println("New peer connected from " + p.getSocketAddress());
                    }

                    String messageContent = new String(p.getData()).trim();
                    System.out.println("Received a signaling message: " + messageContent);
                    Message message = Message.Parse(messageContent);
                    handleMessage(message, peers.get(p.getAddress()));
                }
                System.out.println("Server no longer listening for signaling messages");
            }
        };
        signalingThread.start();
    }

    public void stop()
    {
        running = false;
    }

    protected void handleMessage(Message message, Peer from)
    {
        if (message instanceof ConnectMessage)
        {
            ConnectMessage connectMessage = (ConnectMessage)message;
            from.ufrag = connectMessage.ufrag;
            from.pass = connectMessage.pass;
            System.out.println("Got connection from peer with ufrag " + from.ufrag + " and pass " +
                from.pass);
            iceMediaStream.setRemoteUfrag(from.ufrag);
            iceMediaStream.setRemotePassword(from.pass);
            ConnectMessage connectResponse = new ConnectMessage(iceAgent.getLocalUfrag(), iceAgent.getLocalPassword());
            sendSignalingMessage(from.address, connectResponse);
            sendCandidates(from.address);
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
