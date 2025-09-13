package com.websocket.internal.jetty.springimpl.stomp;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatMessage {
    private String type;
    private String from;
    private String to;      // null for broadcast, userId for private
    private String roomId;
    private String content;

}