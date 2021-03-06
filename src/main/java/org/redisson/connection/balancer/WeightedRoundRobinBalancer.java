/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection.balancer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.redisson.connection.ClientConnectionsEntry;
import org.redisson.misc.URIBuilder;

import io.netty.util.internal.PlatformDependent;

/**
 * Weighted Round Robin balancer.
 *
 * @author Nikita Koksharov
 *
 */
public class WeightedRoundRobinBalancer implements LoadBalancer {

    static class WeightEntry {

        final int weight;
        int weightCounter;

        WeightEntry(int weight) {
            this.weight = weight;
            this.weightCounter = weight;
        }

        public boolean isWeightCounterZero() {
            return weightCounter == 0;
        }

        public void decWeightCounter() {
            weightCounter--;
        }

        public void resetWeightCounter() {
            weightCounter = weight;
        }

    }

    private final AtomicInteger index = new AtomicInteger(-1);

    private final Map<InetSocketAddress, WeightEntry> weights = PlatformDependent.newConcurrentHashMap();

    private final int defaultWeight;

    /**
     * Creates weighted round robin balancer.
     *
     * @param weights - weight mapped by slave node addr in <code>host:port</code> format
     * @param defaultWeight - default weight value assigns to slaves not defined in weights map
     */
    public WeightedRoundRobinBalancer(Map<String, Integer> weights, int defaultWeight) {
        for (Entry<String, Integer> entry : weights.entrySet()) {
            URI uri = URIBuilder.create(entry.getKey());
            InetSocketAddress addr = new InetSocketAddress(uri.getHost(), uri.getPort());
            if (entry.getValue() <= 0) {
                throw new IllegalArgumentException("Weight can't be less than or equal zero");
            }
            this.weights.put(addr, new WeightEntry(entry.getValue()));
        }
        if (defaultWeight <= 0) {
            throw new IllegalArgumentException("Weight can't be less than or equal zero");
        }

        this.defaultWeight = defaultWeight;
    }

    private Set<InetSocketAddress> getAddresses(List<ClientConnectionsEntry> clients) {
        Set<InetSocketAddress> result = new HashSet<InetSocketAddress>();
        for (ClientConnectionsEntry entry : clients) {
            if (entry.isFreezed()) {
                continue;
            }
            result.add(entry.getClient().getAddr());
        }
        return result;
    }

    @Override
    public ClientConnectionsEntry getEntry(List<ClientConnectionsEntry> clients) {
        Set<InetSocketAddress> addresses = getAddresses(clients);

        if (!addresses.equals(weights.keySet())) {
            Set<InetSocketAddress> newAddresses = new HashSet<InetSocketAddress>(addresses);
            newAddresses.removeAll(weights.keySet());
            for (InetSocketAddress addr : newAddresses) {
                weights.put(addr, new WeightEntry(defaultWeight));
            }
        }

        Map<InetSocketAddress, WeightEntry> weightsCopy = new HashMap<InetSocketAddress, WeightEntry>(weights);

        List<ClientConnectionsEntry> clientsCopy = new ArrayList<ClientConnectionsEntry>();

        synchronized (this) {
            for (Iterator<WeightEntry> iterator = weightsCopy.values().iterator(); iterator.hasNext();) {
                WeightEntry entry = iterator.next();

                if (entry.isWeightCounterZero()) {
                    iterator.remove();
                }
            }

            if (weightsCopy.isEmpty()) {
                for (WeightEntry entry : weights.values()) {
                    entry.resetWeightCounter();
                }

                weightsCopy = weights;
            }


            for (InetSocketAddress addr : weightsCopy.keySet()) {
                for (ClientConnectionsEntry clientConnectionsEntry : clients) {
                    if (clientConnectionsEntry.getClient().getAddr().equals(addr)
                            && !clientConnectionsEntry.isFreezed()) {
                        clientsCopy.add(clientConnectionsEntry);
                        break;
                    }
                }
            }

            int ind = Math.abs(index.incrementAndGet() % clientsCopy.size());
            ClientConnectionsEntry entry = clientsCopy.get(ind);
            WeightEntry weightEntry = weights.get(entry.getClient().getAddr());
            weightEntry.decWeightCounter();
            return entry;
        }
    }

}
