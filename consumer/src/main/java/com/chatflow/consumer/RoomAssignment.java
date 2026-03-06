package com.chatflow.consumer;

import java.util.ArrayList;
import java.util.List;

public final class RoomAssignment {
    private RoomAssignment() {
    }

    public static List<List<String>> assignQueues(List<String> queueNames, int workerCount) {
        int effectiveWorkers = Math.max(1, workerCount);
        List<List<String>> assignment = new ArrayList<>(effectiveWorkers);
        for (int i = 0; i < effectiveWorkers; i++) {
            assignment.add(new ArrayList<>());
        }
        for (int i = 0; i < queueNames.size(); i++) {
            assignment.get(i % effectiveWorkers).add(queueNames.get(i));
        }
        return assignment;
    }
}
