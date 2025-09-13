package com.websocket.internal.jetty.springimpl.stomp;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RoomRegistry {
    private final Map<String, Set<String>> roomMembers = new HashMap<>();

    public synchronized void joinRoom(String roomId, String username) {
        roomMembers.computeIfAbsent(roomId, k -> new HashSet<>()).add(username);
    }

    public synchronized void leaveRoom(String roomId, String username) {
        Set<String> members = roomMembers.get(roomId);
        if (members != null) {
            members.remove(username);
            if (members.isEmpty()) {
                roomMembers.remove(roomId);
            }
        }
    }

    public synchronized Set<String> getMembers(String roomId) {
        return roomMembers.getOrDefault(roomId, Collections.emptySet());
    }
}
