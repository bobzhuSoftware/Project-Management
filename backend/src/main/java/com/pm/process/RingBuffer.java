package com.pm.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Thread-safe fixed-capacity FIFO ring buffer of strings. */
public class RingBuffer {

    private final int capacity;
    private final Deque<String> deque;

    public RingBuffer(int capacity) {
        this.capacity = capacity;
        this.deque = new ArrayDeque<>(capacity);
    }

    public synchronized void add(String line) {
        if (deque.size() >= capacity) {
            deque.removeFirst();
        }
        deque.addLast(line);
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<>(deque);
    }

    public synchronized List<String> tail(int n) {
        int size = deque.size();
        if (n >= size) {
            return new ArrayList<>(deque);
        }
        List<String> all = new ArrayList<>(deque);
        return all.subList(size - n, size);
    }

    public synchronized int size() {
        return deque.size();
    }

    public synchronized void clear() {
        deque.clear();
    }
}
