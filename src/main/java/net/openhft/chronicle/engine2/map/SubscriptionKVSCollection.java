package net.openhft.chronicle.engine2.map;

import net.openhft.chronicle.engine2.api.RequestContext;
import net.openhft.chronicle.engine2.api.Subscriber;
import net.openhft.chronicle.engine2.api.Subscription;
import net.openhft.chronicle.engine2.api.TopicSubscriber;
import net.openhft.chronicle.engine2.api.map.KeyValueStore;
import net.openhft.chronicle.engine2.api.map.MapEvent;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Created by peter on 22/05/15.
 */
public class SubscriptionKVSCollection<K, MV, V> implements Subscription {
    final Set<TopicSubscriber<K, V>> topicSubscribers = new CopyOnWriteArraySet<>();
    final Set<Subscriber<KeyValueStore.Entry<K, V>>> subscribers = new CopyOnWriteArraySet<>();
    final Set<Subscriber<K>> keySubscribers = new CopyOnWriteArraySet<>();
    boolean hasSubscribers = false;
    final KeyValueStore<K, MV, V> kvStore;

    public SubscriptionKVSCollection(KeyValueStore<K, MV, V> kvStore) {
        this.kvStore = kvStore;
    }

    public void notifyUpdate(K key, V oldValue, V value) {
        if (hasSubscribers)
            notifyUpdate0(key, oldValue, value);
    }

    private void notifyUpdate0(K key, V oldValue, V value) {
        if (!topicSubscribers.isEmpty()) {
            String key2 = key.toString();
            topicSubscribers.forEach(ts -> ts.onMessage((K) key2, value));
        }
        if (!subscribers.isEmpty()) {
            if (oldValue == null) {
                InsertedEvent<K, V> inserted = InsertedEvent.of(key, value);
                subscribers.forEach(s -> s.on(inserted));

            } else {
                UpdatedEvent<K, V> updated = UpdatedEvent.of(key, oldValue, value);
                subscribers.forEach(s -> s.on(updated));
            }
        }
        if (!keySubscribers.isEmpty()) {
            keySubscribers.forEach(s -> s.on(key));
        }
    }

    public void notifyRemoval(K key, V oldValue) {
        if (hasSubscribers)
            notifyRemoval0(key, oldValue);
    }

    private void notifyRemoval0(K key, V oldValue) {
        if (!topicSubscribers.isEmpty()) {
            String key2 = key.toString();
            topicSubscribers.forEach(ts -> ts.onMessage((K) key2, null));
        }
        if (!subscribers.isEmpty()) {
            RemovedEvent<K, V> removed = RemovedEvent.of(key, oldValue);
            subscribers.forEach(s -> s.on(removed));
        }
        if (!keySubscribers.isEmpty()) {
            keySubscribers.forEach(s -> s.on(key));
        }
    }

    @Override
    public <E> void registerSubscriber(RequestContext rc, Subscriber<E> subscriber) {
        boolean bootstrap = rc.bootstrap();
        Class eClass = rc.type();
        if (eClass == KeyValueStore.Entry.class || eClass == MapEvent.class) {
            subscribers.add((Subscriber) subscriber);
            if (bootstrap) {
                for (int i = 0; i < kvStore.segments(); i++)
                    kvStore.entriesFor(i, e -> subscriber.on((E) InsertedEvent.of(e.key(), e.value())));
            }
        } else {
            keySubscribers.add((Subscriber<K>) subscriber);
            if (bootstrap) {
                for (int i = 0; i < kvStore.segments(); i++)
                    kvStore.keysFor(i, k -> subscriber.on((E) k));
            }
        }
        hasSubscribers = true;
    }

    @Override
    public <T, E> void registerTopicSubscriber(RequestContext rc, TopicSubscriber<T, E> subscriber) {
        boolean bootstrap = rc.bootstrap();
        topicSubscribers.add((TopicSubscriber<K, V>) subscriber);
        if (bootstrap) {
            for (int i = 0; i < kvStore.segments(); i++)
                kvStore.entriesFor(i, (KeyValueStore.Entry<K, V> e) -> subscriber.onMessage((T) e.key(), (E) e.value()));
        }
        hasSubscribers = true;
    }

    @Override
    public void unregisterSubscriber(RequestContext rc, Subscriber subscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterTopicSubscriber(RequestContext rc, TopicSubscriber subscriber) {
        topicSubscribers.remove(subscriber);
        hasSubscribers = !topicSubscribers.isEmpty() && !subscribers.isEmpty();
    }
}