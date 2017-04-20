package org.jitsi.messages;

/**
 * Created by bbaldino on 4/19/17.
 */
public abstract class Message
{
    public static String getMessageType(String messageData)
    {
        return messageData.split(":")[0];
    }

    public static String stripMessageType(String messageData)
    {
        return messageData.substring(messageData.indexOf(":") + 1, messageData.length());
    }

    public static Message Parse(String data)
    {
        String messageType = Message.getMessageType(data);
        if (messageType.equalsIgnoreCase(ConnectMessage.MESSAGE_TYPE))
        {
            String message = Message.stripMessageType(data);
            String[] tokens = message.split("/");
            return new ConnectMessage(tokens[0], tokens[1]);
        }
        else if (messageType.equalsIgnoreCase(CandidateMessage.MESSAGE_TYPE))
        {
            String message = Message.stripMessageType(data);
            return new CandidateMessage(message);
        }
        return null;
    }

    public abstract String toString();
}
