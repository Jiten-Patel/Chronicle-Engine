package net.openhft.chronicle.engine2.session;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.util.Closeable;
import net.openhft.chronicle.engine2.api.*;
import net.openhft.chronicle.engine2.api.map.*;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.TextWire;
import net.openhft.chronicle.wire.Wire;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static net.openhft.chronicle.core.util.StringUtils.split2;
import static net.openhft.chronicle.engine2.api.FactoryContext.factoryContext;

/**
 * Created by peter on 22/05/15.
 */
public class VanillaAsset implements Asset, Closeable {
    private final Asset parent;
    private final String name;
    private final Assetted item;

    private final Map<Class, View> viewMap = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Class, Factory> factoryMap = Collections.synchronizedMap(new LinkedHashMap<>());

    VanillaAsset(FactoryContext<Assetted> context) {
        this.parent = context.parent();
        this.name = context.name();
        this.item = context.item();
        if ("".equals(name)) {
            assert parent == null;
        } else {
            assert parent != null;
            assert name != null;
        }
    }

    @Override
    public Assetted item() {
        return item;
    }

    @Override
    public <V> V getView(Class<V> vClass) {
        View view = viewMap.get(vClass);
        if (view == null && vClass.isInstance(item)) return (V) item;
        return (V) view;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public <V> V acquireView(Class<V> vClass, String queryString) {
        V view = getView(vClass);
        if (view == null) {
            Factory factory = acquireFactory(vClass);
            return (V) viewMap.computeIfAbsent(vClass, v -> (View) factory.create(factoryContext(this).type(v).queryString(queryString)));
        }
        return view;
    }

    @Override
    public <V> V acquireView(Class<V> vClass, Class class1, String queryString) {
        V v = getView(vClass);
        if (v != null)
            return v;
        if (vClass == Set.class) {
            if (class1 == Map.Entry.class) {
                KeyValueStore keyValueStore = acquireView(KeyValueStore.class, class1, queryString);
                return (V) viewMap.computeIfAbsent(EntrySetView.class, aClass ->
                        acquireFactory(EntrySetView.class)
                                .create(factoryContext(VanillaAsset.this).queryString(queryString).item(keyValueStore)));
            }
        }
        if (vClass == TopicPublisher.class) {
            SubscriptionKeyValueStore subscription = acquireView(SubscriptionKeyValueStore.class, class1, queryString);
            return (V) viewMap.computeIfAbsent(TopicPublisher.class, aClass ->
                    acquireFactory(TopicPublisher.class)
                            .create(factoryContext(VanillaAsset.this).queryString(queryString).item(subscription)));
        }
        if (vClass == KeyValueStore.class) {
            KeyValueStore kvStore = (KeyValueStore) viewMap.get(KeyValueStore.class);
            if (kvStore == null) {
                if (item instanceof KeyValueStore)
                    return (V) item;
                else
                    throw new AssetNotFoundException("type: " + vClass);
            }
            return (V) kvStore;
        }
        throw new UnsupportedOperationException("todo " + vClass + " type: " + class1);
    }

    @Override
    public <V> V acquireView(Class<V> vClass, Class class1, Class class2, String queryString) {
        V v = getView(vClass);
        if (v != null)
            return v;
        if (item instanceof KeyValueStore) {
            if ((vClass == Map.class || vClass == ConcurrentMap.class) && item instanceof KeyValueStore) {
                return (V) acquireView(MapView.class, class1, class2, queryString);
            }
            if (vClass == MapView.class) {
                View view = viewMap.computeIfAbsent(MapView.class, aClass -> {
                    KeyValueStore kvStore;
                    if (class1 == String.class) {
                        if (class2 == BytesStore.class) {
                            kvStore = acquireView(StringBytesStoreKeyValueStore.class, class1, class2, queryString);
                        } else if (Marshallable.class.isAssignableFrom(class2)) {
                            kvStore = acquireView(StringMarshallableKeyValueStore.class, class1, class2, queryString);
                        } else {
                            kvStore = (KeyValueStore) item;
                        }
                    } else {
                        kvStore = (KeyValueStore) item;
                    }
                    return acquireFactory(MapView.class)
                            .create(factoryContext(VanillaAsset.this).type(class1).type2(class2).queryString(queryString).item(kvStore));
                });
                return (V) view;
            }
            if (vClass == StringMarshallableKeyValueStore.class) {
                View view = viewMap.computeIfAbsent(StringMarshallableKeyValueStore.class, aClass -> {
                    KeyValueStore kvStore = acquireView(StringBytesStoreKeyValueStore.class, String.class, BytesStore.class, queryString);
                    StringMarshallableKeyValueStore smkvStore = acquireFactory(StringMarshallableKeyValueStore.class)
                            .create(factoryContext(VanillaAsset.this)
                                    .type(class1).type2(class2)
                                    .queryString(queryString)
                                    .wireType((Function<Bytes, Wire>) TextWire::new)
                                    .item(kvStore));
                    viewMap.put(Subscription.class, smkvStore);
                    viewMap.put(KeyValueStore.class, smkvStore);
                    return smkvStore;
                });
                return (V) view;
            }
            if (vClass == StringBytesStoreKeyValueStore.class) {
                return (V) item;
            }
        }
        throw new UnsupportedOperationException("todo " + vClass + " type: " + class1 + " type2: " + class2);
    }


    @Override
    public <I> Factory<I> acquireFactory(Class<I> iClass) throws AssetNotFoundException {
        Factory<I> factory = factoryMap.get(iClass);
        if (factory != null)
            return factory;
        try {
            if (parent == null) {
                throw new AssetNotFoundException("Cannot find or build an factory for " + iClass);
            }
            return parent.acquireFactory(iClass);
        } catch (AssetNotFoundException e) {
            if (iClass != View.class) {
                Factory<Factory> factoryFactory = factoryMap.get(Factory.class);
                if (factoryFactory != null) {
                    factory = factoryFactory.create(factoryContext(this).type(iClass));
                    if (factory != null) {
                        factoryMap.put(iClass, factory);
                        return factory;
                    }
                }
            }
            throw e;
        }
    }

    @Override
    public <I> void registerView(Class<I> iClass, I interceptor) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public <E> void registerSubscriber(Class<E> eClass, Subscriber<E> subscriber, String query) {
        Subscription sub = acquireView(Subscription.class, query);
        sub.registerSubscriber(eClass, subscriber, query);
    }

    @Override
    public <T, E> void registerTopicSubscriber(Class<E> eClass, TopicSubscriber<T, E> subscriber, String query) {
        Subscription sub = acquireView(Subscription.class, query);
        sub.registerTopicSubscriber(eClass, subscriber, query);
    }

    @Override
    public <E> void unregisterSubscriber(Class<E> eClass, Subscriber<E> subscriber, String query) {
        Subscription sub = getView(Subscription.class);
        if (sub != null)
            sub.unregisterSubscriber(eClass, subscriber, query);
    }

    @Override
    public <T, E> void unregisterTopicSubscriber(Class<E> eClass, TopicSubscriber<T, E> subscriber, String query) {
        Subscription sub = getView(Subscription.class);
        if (sub != null)
            sub.unregisterTopicSubscriber(eClass, subscriber, query);
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public Asset parent() {
        return parent;
    }

    @NotNull
    @Override
    public Stream<Asset> children() {
        return children.values().stream();
    }

    final ConcurrentMap<String, Asset> children = new ConcurrentSkipListMap<>();

    @NotNull
    @Override
    public <A> Asset acquireChild(String name, Class<A> assetClass, Class class1, Class class2) throws
            AssetNotFoundException {
        int pos = name.indexOf("/");
        if (pos >= 0) {
            String name1 = name.substring(0, pos);
            String name2 = name.substring(pos + 1);
            return getAssetOrANFE(name1, assetClass, class1, class2).acquireChild(name2, assetClass, class1, class2);
        }
        return getAssetOrANFE(name, assetClass, class1, class2);
    }

    private <A> Asset getAssetOrANFE(String name, Class<A> assetClass, Class class1, Class class2) throws AssetNotFoundException {
        Asset asset = children.get(name);
        if (asset == null) {
            asset = createAsset(name, assetClass, class1, class2);
            if (asset == null)
                throw new AssetNotFoundException(name);
        }
        return asset;
    }

    @Nullable
    protected <A> Asset createAsset(String name, Class<A> assetClass, Class class1, Class class2) {
        if (assetClass == null)
            return null;
        String[] nameQuery = split2(name, '?');
        if (assetClass == Map.class || assetClass == ConcurrentMap.class) {
            Factory<KeyValueStore> kvStoreFactory = acquireFactory(KeyValueStore.class);
            KeyValueStore resource = kvStoreFactory.create(factoryContext(this).name(nameQuery[0]).queryString(nameQuery[1]).type(class1).type2(class2));
            return add(nameQuery[0], resource);

        } else if (assetClass == String.class && item instanceof KeyValueStore) {
            Factory<SubAsset> subAssetFactory = acquireFactory(SubAsset.class);
            SubAsset value = subAssetFactory.create(factoryContext(this).name(nameQuery[0]).queryString(nameQuery[1]));
            children.put(nameQuery[0], value);
            return value;

        } else if (assetClass == Void.class) {
            Factory<Asset> factory = acquireFactory(Asset.class);
            Asset asset = factory.create(factoryContext(this).name(nameQuery[0]).queryString(nameQuery[1]));
            children.put(nameQuery[0], asset);
            return asset;

        } else {
            throw new UnsupportedOperationException("todo name:" + name + " asset " + assetClass);
        }
    }

    @Override
    public Asset getChild(String name) {
        int pos = name.indexOf("/");
        if (pos >= 0) {
            String name1 = name.substring(0, pos);
            String name2 = name.substring(pos + 1);
            Asset asset = getAsset(name1);
            if (asset == null) {
                return null;

            } else {
                return asset.getChild(name2);
            }
        }
        return getAsset(name);
    }

    @Nullable
    private Asset getAsset(String name) {
        return children.get(name);
    }

    @Override
    public void removeChild(String name) {
        throw new UnsupportedOperationException("todo");
    }

    public Asset add(String name, Assetted resource) {
        int pos = name.indexOf("/");
        if (pos >= 0) {
            String name1 = name.substring(0, pos);
            String name2 = name.substring(pos + 1);
            getAssetOrANFE(name1, null, null, null).add(name2, resource);
        }
        if (children.containsKey(name))
            throw new IllegalStateException(name + " already exists");
        Factory<Asset> factory = acquireFactory(Asset.class);
        Asset asset = factory.create(factoryContext(this).name(name).item(resource));
        children.put(name, asset);
        return asset;
    }

    @Override
    public <I> void registerFactory(Class<I> iClass, Factory<I> factory) {
        factoryMap.put(iClass, factory);
    }

    @Override
    public String toString() {
        return (item == null ? "node" : item.getClass().getSimpleName()) + "@" + fullName();
    }
}
