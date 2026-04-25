package moe.maika.fmteamhundo.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * A thread-safe ring buffer that maintains elements in sorted order (newest first)
 * and automatically evicts the oldest elements (by sorted order) when full.
 * 
 * This implementation allows duplicate elements (same timestamp is fine) and
 * always keeps the N most recent items based on their natural ordering.
 */
public class RingBuffer<E extends Comparable<E>> implements Iterable<E>, Serializable {

    private final List<E> elements;
    private final int maxSize;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public RingBuffer(int maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be non-negative");
        }
        this.elements = new ArrayList<>();
        this.maxSize = maxSize;
    }

    /**
     * Adds an element to the buffer. The buffer maintains sorted order (newest first).
     * If the buffer exceeds maxSize, the oldest element (last in sorted order) is removed.
     * 
     * @param e element to add
     * @return true always
     */
    public boolean add(E e) {
        if (maxSize == 0) {
            return true;
        }
        
        lock.writeLock().lock();
        try {
            // Insert in sorted position (newest first)
            insertInSortedOrder(e);
            
            // If we exceeded maxSize, remove the oldest (last element)
            while (elements.size() > maxSize) {
                elements.remove(elements.size() - 1);
            }
            
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Inserts an element into the sorted list maintaining descending order
     * (newest first based on Comparable implementation)
     */
    private void insertInSortedOrder(E e) {
        int index = 0;
        while (index < elements.size()) {
            // Find position where this element should go
            // If current element is "older" than new element, insert before it
            if (elements.get(index).compareTo(e) < 0) {
                break;
            }
            index++;
        }
        elements.add(index, e);
    }

    /**
     * Adds all elements from a collection. Elements are added in collection order,
     * but the buffer will maintain sorted order and evict the oldest as needed.
     * 
     * @param collection elements to add
     * @return true always
     */
    public boolean addAll(Collection<? extends E> collection) {
        if (collection.isEmpty() || maxSize == 0) {
            return true;
        }
        
        lock.writeLock().lock();
        try {
            for (E element : collection) {
                insertInSortedOrder(element);
            }
            
            // Remove oldest elements if we exceeded maxSize
            while (elements.size() > maxSize) {
                elements.remove(elements.size() - 1);
            }
            
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean replaceAll(E replacement, Function<E, Boolean> filter) {
        boolean replaced = false;
        lock.writeLock().lock();
        try {
            for(int i = 0, n = elements.size(); i < n; i++) {
                Boolean decision = filter.apply(elements.get(i));
                if(decision == null) {
                    return true;
                }
                if(decision) {
                    elements.set(i, replacement);
                    replaced = true;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        return replaced;
    }

    /**
     * If true, replace.
     * If false, add if never null.
     * If null, stop trying to replace and don't add.
     * 
     * @param element
     * @param replaceCondition
     */
    public void addOrReplace(E element, Function<E, Boolean> replaceCondition) {
        lock.writeLock().lock();
        try {
            boolean replaced = replaceAll(element, replaceCondition);
            if(!replaced) {
                add(element);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns elements in sorted order (newest first)
     */
    public List<E> toList() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(elements);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return elements.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return elements.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            elements.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Iterator<E> iterator() {
        return toList().iterator();
    }
}