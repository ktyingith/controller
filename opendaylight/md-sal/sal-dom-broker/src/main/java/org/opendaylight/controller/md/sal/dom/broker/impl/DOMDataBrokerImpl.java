/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.md.sal.dom.broker.impl;

import static com.google.common.base.Preconditions.checkState;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionChainListener;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataChangeListener;
import org.opendaylight.controller.md.sal.dom.api.DOMDataWriteTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMTransactionChain;
import org.opendaylight.controller.sal.core.spi.data.DOMStore;
import org.opendaylight.controller.sal.core.spi.data.DOMStoreThreePhaseCommitCohort;
import org.opendaylight.controller.sal.core.spi.data.DOMStoreTransactionChain;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DOMDataBrokerImpl extends AbstractDOMForwardedTransactionFactory<DOMStore> implements DOMDataBroker,
        AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DOMDataBrokerImpl.class);

    private final DOMDataCommitExecutor coordinator;
    private final AtomicLong txNum = new AtomicLong();
    private final AtomicLong chainNum = new AtomicLong();
    private volatile AutoCloseable closeable;

    public DOMDataBrokerImpl(final Map<LogicalDatastoreType, DOMStore> datastores,
            final ListeningExecutorService executor) {
        this(datastores, new DOMDataCommitCoordinatorImpl(executor));
    }

    public DOMDataBrokerImpl(final Map<LogicalDatastoreType, DOMStore> datastores,
            final DOMDataCommitExecutor coordinator) {
        super(datastores);
        this.coordinator = Preconditions.checkNotNull(coordinator);
    }

    public void setCloseable(final AutoCloseable closeable) {
        this.closeable = closeable;
    }

    @Override
    public void close() {
        super.close();

        if(closeable != null) {
            try {
                closeable.close();
            } catch(Exception e) {
                LOG.debug("Error closing instance", e);
            }
        }
    }

    @Override
    protected Object newTransactionIdentifier() {
        return "DOM-" + txNum.getAndIncrement();
    }

    @Override
    public ListenerRegistration<DOMDataChangeListener> registerDataChangeListener(final LogicalDatastoreType store,
            final YangInstanceIdentifier path, final DOMDataChangeListener listener, final DataChangeScope triggeringScope) {

        DOMStore potentialStore = getTxFactories().get(store);
        checkState(potentialStore != null, "Requested logical data store is not available.");
        return potentialStore.registerChangeListener(path, listener, triggeringScope);
    }

    @Override
    public DOMTransactionChain createTransactionChain(final TransactionChainListener listener) {
        checkNotClosed();

        final Map<LogicalDatastoreType, DOMStoreTransactionChain> backingChains = new EnumMap<>(LogicalDatastoreType.class);
        for (Entry<LogicalDatastoreType, DOMStore> entry : getTxFactories().entrySet()) {
            backingChains.put(entry.getKey(), entry.getValue().createTransactionChain());
        }

        final long chainId = chainNum.getAndIncrement();
        LOG.debug("Transactoin chain {} created with listener {}, backing store chains {}", chainId, listener,
                backingChains);
        return new DOMDataBrokerTransactionChainImpl(chainId, backingChains, coordinator, listener);

    }

    @Override
    public CheckedFuture<Void,TransactionCommitFailedException> submit(final DOMDataWriteTransaction transaction,
            final Iterable<DOMStoreThreePhaseCommitCohort> cohorts) {
        LOG.debug("Transaction: {} submitted with cohorts {}.", transaction.getIdentifier(), cohorts);
        return coordinator.submit(transaction, cohorts);
    }
}
