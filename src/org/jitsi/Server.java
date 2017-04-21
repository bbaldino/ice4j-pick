package org.jitsi;

import org.ice4j.Transport;
import org.ice4j.ice.*;
import org.ice4j.socket.MultiplexedDatagramSocket;
import org.ice4j.socket.MultiplexingDatagramSocket;
import org.jitsi.messages.CandidateMessage;
import org.jitsi.messages.ConnectMessage;
import org.jitsi.messages.Message;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

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
    protected Map<InetAddress, Peer> peers = new HashMap<>();

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
                MultiplexingDatagramSocket s = iceMediaStream.getComponents().get(0).getSocket();
                startDataLoops(s);
            }
        });

        for (LocalCandidate candidate : component.getLocalCandidates())
        {
            System.out.println("Got local candidate: " + candidate);
        }
    }

    protected int getPacketType(DatagramPacket packet)
    {
        return ByteBuffer.wrap(packet.getData()).getInt();
    }

    Map<Integer, Integer> packetTypeCounts = new HashMap<>();
    long firstPacketTime = -1;
    protected Thread createDataLoop(String name, MultiplexingDatagramSocket socket, int packetType)
    {
        return new Thread(name) {
            @Override
            public void run()
            {
                byte[] data = new byte[1500];
                DatagramPacket p = new DatagramPacket(data, 1500);
                MultiplexedDatagramSocket s = null;
                int numPacketsReceived = 0;
                packetTypeCounts.put(packetType, 0);
                try
                {
                    s = socket.getSocket(packet -> getPacketType(packet) == packetType);
                    long currTime = System.nanoTime();
                    if (firstPacketTime == -1 || currTime < firstPacketTime)
                    {
                        firstPacketTime = currTime;
                    }
                } catch (SocketException e)
                {
                    e.printStackTrace();
                }
                while (running)
                {
                    try
                    {
                        s.receive(p);
                        numPacketsReceived++;
                        packetTypeCounts.put(packetType, packetTypeCounts.get(packetType) + 1);
                    }
                    catch (SocketTimeoutException e)
                    {
                        if (numPacketsReceived > 0)
                        {
                            System.out.println(name + " thread done receiving, received " + numPacketsReceived + " packets");
                            break;
                        }

                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        };

    }

    protected void startDataLoops(MultiplexingDatagramSocket socket)
    {
        byte[] data = new byte[1500];
        DatagramPacket p = new DatagramPacket(data, 1500);

        try
        {
            socket.setSoTimeout(100);
            socket.setReceiveBufferSize(106496);
            System.out.println("Receive socket buffer size is " + socket.getReceiveBufferSize());
        } catch (SocketException e)
        {
            System.out.println("Error setting socket config " + e.toString());
        }

        long startTime = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        Thread audioRtp =
                createDataLoop("audio rtp", socket, AUDIO_RTP_PACKET_TYPE);
        threads.add(audioRtp);
        audioRtp.start();

        Thread audioRtcp =
                createDataLoop("audio rtcp", socket, AUDIO_RTCP_PACKET_TYPE);
        threads.add(audioRtcp);
        audioRtcp.start();

        Thread videoRtp =
                createDataLoop("video rtp", socket, VIDEO_RTP_PACKET_TYPE);
        threads.add(videoRtp);
        videoRtp.start();

        Thread videoRtcp =
                createDataLoop("video rtcp", socket, VIDEO_RTCP_PACKET_TYPE);
        threads.add(videoRtcp);
        videoRtcp.start();

        Thread dtls =
                createDataLoop("dtls", socket, DTLS_PACKET_TYPE);
        threads.add(dtls);
        dtls.start();

        new Thread("waiting thread") {
            @Override
            public void run()
            {
                for (Thread t : threads)
                {
                    try
                    {
                        t.join();
                    } catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                }
                long finishTime = System.nanoTime();
                System.out.println("All threads finished, packet counts: " + packetTypeCounts.toString());
                long totalPackets = 0;
                for (Map.Entry<Integer, Integer> e : packetTypeCounts.entrySet())
                {
                    totalPackets += e.getValue();
                }
                System.out.println("Coarse start time: " + startTime + ", finer start time: " + firstPacketTime);
                // The socket timeout is 100milliseconds, so we'll subtract that here when calculating
                //  the total time it took
                System.out.println("Received " + totalPackets + " packets total in " +
                        (finishTime - firstPacketTime - 100000000) + " nanoseconds at a rate of " +
                        getBitrateMbps(totalPackets * PACKET_SIZE_BYTES,
                                (finishTime - firstPacketTime - 100000000)));
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
