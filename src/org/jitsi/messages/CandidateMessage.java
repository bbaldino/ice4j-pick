package org.jitsi.messages;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.jitsi.Server;

/**
 * Created by bbaldino on 4/19/17.
 */
public class CandidateMessage
    extends Message
{
    protected static String MESSAGE_TYPE = "candidate";

    protected LocalCandidate candidate = null;

    protected String rawData = null;


    public CandidateMessage (LocalCandidate candidate)
    {
        this.candidate = candidate;
    }

    // When we receive a CandidateMessage, we'll want it as a RemoteCandidate object,
    //  but to create a RemoteCandidate we need the component and the Message::Parse
    //  method won't have that, so just store the raw data and the caller can call
    //  toRemoteCandidate from a place where the component is accessible
    public CandidateMessage (String rawData)
    {
        this.rawData = rawData;
    }

    @Override
    public String toString()
    {
        return MESSAGE_TYPE + ":" +
                String.join("/",
                                candidate.getUfrag(),
                                candidate.getTransportAddress().toString(),
                                String.valueOf(candidate.getParentComponent().getComponentID()),
                                candidate.getType().toString(),
                                candidate.getFoundation().toString(),
                                String.valueOf(candidate.getPriority())
                        );
    }

    public RemoteCandidate toRemoteCandidate(Component component)
    {
        String[] tokens = this.rawData.split("/");

        String ufrag = tokens[0];
        String hostIp = tokens[1];

        String host = hostIp.substring(0, hostIp.lastIndexOf(":"));
        int port = Integer.valueOf(hostIp.substring(hostIp.lastIndexOf(":") + 1, hostIp.length()));
        String protocol = tokens[2];
        //int componentId = Integer.valueOf(tokens[3]);
        CandidateType type = CandidateType.parse(tokens[4]);
        String foundation = tokens[5];
        long priority = Long.valueOf(tokens[6]);

        TransportAddress address = new TransportAddress(
                host,
                port,
                Transport.parse(protocol));

        return new RemoteCandidate(
                address,
                component,
                type,
                foundation,
                priority,
                null,
                ufrag);
    }
}
