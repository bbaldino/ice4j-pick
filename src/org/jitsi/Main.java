package org.jitsi;

import java.io.IOException;
import java.net.SocketException;

public class Main {

    public static void main(String[] args)
        throws SocketException, IOException
    {
        if (args.length > 0)
        {
            String serverIp = args[0];
            int serverPort = Integer.valueOf(args[1]);
            System.out.println("Client connecting to " + serverIp + ":" + serverPort);
            Client c = new Client(serverIp, serverPort);
            c.startSignalingLoop();
        }
        else
        {
            Server s = new Server();
            s.startSignaling();
            try
            {
                Thread.sleep(600000);
            } catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }
    }
}
