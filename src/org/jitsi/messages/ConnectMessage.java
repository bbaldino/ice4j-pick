package org.jitsi.messages;

/**
 * Created by bbaldino on 4/19/17.
 */
public class ConnectMessage
    extends Message
{
    protected static String MESSAGE_TYPE = "connect";
    public String ufrag;
    public String pass;

    public ConnectMessage(String ufrag, String pass)
    {
        this.ufrag = ufrag;
        this.pass = pass;
    }

    @Override
    public String toString()
    {
        return MESSAGE_TYPE + ":" + ufrag + "/" + pass;
    }
}
