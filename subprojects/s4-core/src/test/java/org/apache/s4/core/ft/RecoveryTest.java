/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.s4.core.ft;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.I0Itec.zkclient.IZkChildListener;
import org.apache.s4.base.Event;
import org.apache.s4.base.EventMessage;
import org.apache.s4.base.SerializerDeserializer;
import org.apache.s4.comm.tcp.TCPEmitter;
import org.apache.s4.comm.topology.ZkClient;
import org.apache.s4.fixtures.CoreTestUtils;
import org.apache.s4.fixtures.ZkBasedTest;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.Test;

import com.google.inject.Injector;

public class RecoveryTest extends ZkBasedTest {

    private Process forkedS4App = null;

    @After
    public void cleanup() throws Exception {
        CoreTestUtils.killS4App(forkedS4App);
    }

    @Test
    public void testCheckpointRestorationThroughApplicationEvent() throws Exception {

        testCheckpointingConfiguration(S4AppWithManualCheckpointing.class,
                FileSystemBasedBackendWithZKStorageCallbackCheckpointingModule.class, true,
                "value1=message1 ; value2=message2");

    }

    @Test
    public void testEventCountBasedCheckpointingAndRecovery() throws Exception {

        testCheckpointingConfiguration(S4AppWithCountBasedCheckpointing.class,
                FileSystemBasedBackendWithZKStorageCallbackCheckpointingModule.class, false,
                "value1=message1 ; value2=message2");

    }

    @Test
    public void testTimeBasedCheckpointingAndRecovery() throws Exception {
        testCheckpointingConfiguration(S4AppWithTimeBasedCheckpointing.class,
                FileSystemBasedBackendWithZKStorageCallbackCheckpointingModule.class, false,
                "value1=message1 ; value2=message2");
    }

    @Test
    public void testTimingOutRecovery() throws Exception {
        testCheckpointingConfiguration(S4AppWithCountBasedCheckpointing.class,
                CheckpointingModuleWithUnrespondingFetchingStorageBackend.class, false, "value1= ; value2=message2");
    }

    private void insertCheckpointInstruction(Injector injector, TCPEmitter emitter) {
        Event event;
        event = new Event();
        event.put("command", String.class, "checkpoint");
        emitter.send(0, new EventMessage("-1", "inputStream", injector.getInstance(SerializerDeserializer.class)
                .serialize(event)));
    }

    private void testCheckpointingConfiguration(Class<?> appClass, Class<?> backendModuleClass,
            boolean manualCheckpointing, String expectedFinalResult) throws IOException, InterruptedException,
            KeeperException {
        // here checkpointing is automatic for every event: no need to send a "checkpoint" event. The checkpointing
        // configuration is specified in the app (S4AppWithCountBasedCheckpointing class)

        final ZooKeeper zk = CoreTestUtils.createZkClient();

        // use a latch for waiting for app to be ready
        CountDownLatch signalConsumerReady = getConsumerReadySignal("inputStream");

        // 1. instantiate remote S4 app
        forkedS4App = CoreTestUtils.forkS4Node(new String[] { "-c", "cluster1", "-appClass", appClass.getName(),
                "-extraModulesClasses", backendModuleClass.getName() });

        Assert.assertTrue(signalConsumerReady.await(20, TimeUnit.SECONDS));

        CountDownLatch signalValue1Set = new CountDownLatch(1);
        CoreTestUtils.watchAndSignalCreation("/value1Set", signalValue1Set, zk);
        final CountDownLatch signalCheckpointed = new CountDownLatch(1);
        CoreTestUtils.watchAndSignalCreation("/checkpointed", signalCheckpointed, zk);

        Injector injector = CoreTestUtils.createInjectorWithNonFailFastZKClients();

        TCPEmitter emitter = injector.getInstance(TCPEmitter.class);

        Event event = new Event();
        event.put("command", String.class, "setValue1");
        event.put("value", String.class, "message1");
        emitter.send(0, new EventMessage("-1", "inputStream", injector.getInstance(SerializerDeserializer.class)
                .serialize(event)));

        if (manualCheckpointing) {
            insertCheckpointInstruction(injector, emitter);
        }

        Assert.assertTrue(signalCheckpointed.await(10, TimeUnit.SECONDS));

        forkedS4App.destroy();

        zk.delete("/data", -1);

        signalConsumerReady = getConsumerReadySignal("inputStream");
        forkedS4App = CoreTestUtils.forkS4Node(new String[] { "-c", "cluster1", "-appClass",
                S4AppWithManualCheckpointing.class.getName(), "-extraModulesClasses", backendModuleClass.getName() });

        Assert.assertTrue(signalConsumerReady.await(20, TimeUnit.SECONDS));
        // // trigger recovery by sending application event to set value 2
        CountDownLatch signalValue2Set = new CountDownLatch(1);
        CoreTestUtils.watchAndSignalCreation("/value2Set", signalValue2Set, zk);

        event = new Event();
        event.put("command", String.class, "setValue2");
        event.put("value", String.class, "message2");
        emitter.send(0, new EventMessage("-1", "inputStream", injector.getInstance(SerializerDeserializer.class)
                .serialize(event)));

        Assert.assertTrue(signalValue2Set.await(10, TimeUnit.SECONDS));

        Assert.assertEquals(expectedFinalResult, new String(zk.getData("/data", false, null)));
    }

    public static CountDownLatch getConsumerReadySignal(String streamName) {
        final CountDownLatch signalAppReady = new CountDownLatch(1);

        ZkClient zkClient = new ZkClient("localhost:" + CoreTestUtils.ZK_PORT);
        // TODO check a proper app state variable. This is hacky
        zkClient.subscribeChildChanges("/s4/streams/" + streamName + "/consumers", new IZkChildListener() {

            @Override
            public void handleChildChange(String parentPath, List<String> currentChilds) throws Exception {
                if (currentChilds.size() == 1) {
                    signalAppReady.countDown();
                }

            }
        });
        return signalAppReady;
    }
}
