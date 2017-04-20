package org.jitsi;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.jitsi.messages.CandidateMessage;
import org.jitsi.messages.Message;

import java.io.IOException;
import java.net.*;

/**
 * Created by bbaldino on 4/17/17.
 */
public class ClientServerCommon
{
    /**
     * Raw socket used to receive and send signaling messages
     */
    protected DatagramSocket signalingSocket;

    /**
     * Thread used to send and receive signaling messages
     */
    protected Thread signalingThread;

    /**
     * The ICE agent used for the data connection
     */
    protected Agent iceAgent;

    protected boolean running = true;

    protected IceMediaStream iceMediaStream;

    protected void sendSignalingMessage(SocketAddress to, Message message)
    {
        byte[] messageBytes = message.toString().getBytes();

        DatagramPacket p = new DatagramPacket(messageBytes, messageBytes.length);
        p.setSocketAddress(to);
        try
        {
            signalingSocket.send(p);
        } catch (IOException e)
        {
            System.out.println("Error sending signaling message: " + e.toString());
        }

    }

    protected void sendSignalingMessage(Message message)
    {
        sendSignalingMessage(signalingSocket.getRemoteSocketAddress(), message);
    }

    protected Message waitForMessage()
    {
        byte[] buf = new byte[1500];
        DatagramPacket p = new DatagramPacket(buf, 1500);
        try
        {
            signalingSocket.receive(p);
        } catch (IOException e)
        {
            System.out.println("Error receiving signaling response: " + e.toString());
            return null;
        }
        System.out.println("Got message " + new String(p.getData()).trim());
        return Message.Parse(new String(p.getData()).trim());
    }

    protected void sendCandidates(SocketAddress to)
    {
        Component component = iceMediaStream.getComponents().get(0);
        for (LocalCandidate candidate : component.getLocalCandidates())
        {
            candidate.setUfrag(iceAgent.getLocalUfrag());
            System.out.println("Sending candidate to " + to.toString());
            sendSignalingMessage(to, new CandidateMessage(candidate));
        }
    }

    protected void sendCandidates()
    {
        sendCandidates(signalingSocket.getRemoteSocketAddress());
    }

}
