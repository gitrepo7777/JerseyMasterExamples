/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.examples.sse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.ChunkedInput;
import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.EventSource;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.glassfish.jersey.media.sse.SseFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
public class ServerSentEventsTest extends JerseyTest {

    @Override
    protected Application configure() {
        // enable(TestProperties.LOG_TRAFFIC);
        return new ResourceConfig(ServerSentEventsResource.class, DomainResource.class, SseFeature.class);
    }

    @Test
    public void testEventSource() throws InterruptedException, URISyntaxException {

        final CountDownLatch latch = new CountDownLatch(1);
        final EventSource eventSource = new EventSource(target().path(App.ROOT_PATH)) {
            @Override
            public void onEvent(InboundEvent inboundEvent) {
                try {
                    System.out.println("# Received: " + inboundEvent);
                    System.out.println(inboundEvent.getData(String.class));

                    assertEquals("message", inboundEvent.getData());
                    latch.countDown();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        target().path(App.ROOT_PATH).request().post(Entity.text("message"));
//        target().path(App.ROOT_PATH).request().delete();

        try {
            latch.await(5, TimeUnit.SECONDS);
        } finally {
            eventSource.close();
        }
    }

    @Test
    public void testInboundEventReader() throws InterruptedException {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch stopLatch = new CountDownLatch(5);
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final WebTarget target = target(App.ROOT_PATH);
                target.register(SseFeature.class);
                final EventInput eventInput = target.request().get(EventInput.class);

                startLatch.countDown();

                try {
                    eventInput.setParser(ChunkedInput.createParser("\n\n"));
                    do {
                        InboundEvent event = eventInput.read();
                        System.out.println("# Received: " + event);
                        System.out.println(event.getData(String.class));

                        assertEquals("message " + (5 - stopLatch.getCount()), event.getData());
                        stopLatch.countDown();
                    } while (stopLatch.getCount() > 0);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (eventInput != null) {
                        eventInput.close();
                    }
                }
            }
        });
        thread.start();

        assertTrue(startLatch.await(5, TimeUnit.SECONDS));

        for (int i = 0; i < 5; i++) {
            target(App.ROOT_PATH).request().post(Entity.text("message " + i));
        }
        assertTrue(stopLatch.await(5, TimeUnit.SECONDS));
        thread.join(5000);
    }

    @Test
    public void testCreateDomain() throws InterruptedException, URISyntaxException {
        final int MAX_COUNT = 25;

        // I don't really care what data are there (don't want to add too much complexity for this sample)
        final Response response = target().path("domain/start").queryParam("testSources", MAX_COUNT)
                .request().post(Entity.text("data"), Response.class);

        final AtomicInteger doneCount = new AtomicInteger(0);
        final CountDownLatch doneLatch = new CountDownLatch(MAX_COUNT);
        final EventSource[] sources = new EventSource[MAX_COUNT];
        final String processUrl = response.getLocation().toString();
        for (int i = 0; i < MAX_COUNT; i++) {
            sources[i] = new EventSource(target().path(processUrl).queryParam("testSource", "true"), false);
            sources[i].register(new EventListener() {

                private volatile int messageCount = 0;

                @Override
                public void onEvent(InboundEvent inboundEvent) {
                    try {
                        messageCount++;

                        System.out.println("# Received: " + inboundEvent);

                        if (inboundEvent.getData(String.class).equals("done")) {
                            assertEquals(6, messageCount);
                            doneCount.incrementAndGet();
                            doneLatch.countDown();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            sources[i].open();
        }

        doneLatch.await(2, TimeUnit.SECONDS);
        System.out.println("done");

        for (EventSource source : sources) {
            source.close();
        }

        System.out.println("terminated");
        assertEquals(MAX_COUNT, doneCount.get());
    }
}
