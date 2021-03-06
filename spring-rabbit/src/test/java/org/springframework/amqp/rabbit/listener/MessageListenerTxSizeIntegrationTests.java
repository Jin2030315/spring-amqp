/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.amqp.rabbit.listener;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.junit.BrokerRunning;
import org.springframework.amqp.rabbit.junit.BrokerTestUtils;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.rabbit.test.LogLevelAdjuster;
import org.springframework.beans.factory.DisposableBean;

import com.rabbitmq.client.Channel;

/**
 * @author Dave Syer
 * @author Gunnar Hillert
 * @author Gary Russell
 *
 * @since 1.0
 *
 */
public class MessageListenerTxSizeIntegrationTests {

	private static Log logger = LogFactory.getLog(MessageListenerTxSizeIntegrationTests.class);

	private final Queue queue = new Queue("test.queue");

	private final RabbitTemplate template = new RabbitTemplate();

	private final int concurrentConsumers = 1;

	private final int messageCount = 12;

	private final int txSize = 4;

	private boolean transactional = true;

	private SimpleMessageListenerContainer container;

	@Rule
	public LogLevelAdjuster logLevels = new LogLevelAdjuster(Level.ERROR, RabbitTemplate.class,
			SimpleMessageListenerContainer.class, BlockingQueueConsumer.class);

	@Rule
	public BrokerRunning brokerIsRunning = BrokerRunning.isRunningWithEmptyQueues(queue.getName());

	@Before
	public void createConnectionFactory() {
		CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
		connectionFactory.setHost("localhost");
		connectionFactory.setChannelCacheSize(concurrentConsumers);
		connectionFactory.setPort(BrokerTestUtils.getPort());
		template.setConnectionFactory(connectionFactory);
	}

	@After
	public void clear() throws Exception {
		// Wait for broker communication to finish before trying to stop container
		Thread.sleep(300L);
		logger.debug("Shutting down at end of test");
		if (container != null) {
			container.shutdown();
		}

		((DisposableBean) template.getConnectionFactory()).destroy();
		this.brokerIsRunning.removeTestQueues();
	}

	@Test
	public void testListenerTransactionalSunnyDay() throws Exception {
		transactional = true;
		CountDownLatch latch = new CountDownLatch(messageCount);
		container = createContainer(new TestListener(latch, false));
		for (int i = 0; i < messageCount; i++) {
			template.convertAndSend(queue.getName(), i + "foo");
		}
		int timeout = Math.min(1 + messageCount / (4 * concurrentConsumers), 30);
		logger.debug("Waiting for messages with timeout = " + timeout + " (s)");
		boolean waited = latch.await(timeout, TimeUnit.SECONDS);
		assertTrue("Timed out waiting for message", waited);
		assertNull(template.receiveAndConvert(queue.getName()));
	}

	@Test
	public void testListenerTransactionalFails() throws Exception {
		transactional = true;
		CountDownLatch latch = new CountDownLatch(messageCount);
		container = createContainer(new TestListener(latch, true));
		for (int i = 0; i < txSize; i++) {
			template.convertAndSend(queue.getName(), i + "foo");
		}
		int timeout = Math.min(1 + messageCount / (4 * concurrentConsumers), 30);
		logger.debug("Waiting for messages with timeout = " + timeout + " (s)");
		boolean waited = latch.await(timeout, TimeUnit.SECONDS);
		assertTrue("Timed out waiting for message", waited);
		assertNull(template.receiveAndConvert(queue.getName()));
	}

	private SimpleMessageListenerContainer createContainer(Object listener) {
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(template.getConnectionFactory());
		container.setMessageListener(new MessageListenerAdapter(listener));
		container.setQueueNames(queue.getName());
		container.setTxSize(txSize);
		container.setPrefetchCount(txSize);
		container.setConcurrentConsumers(concurrentConsumers);
		container.setChannelTransacted(transactional);
		container.setAcknowledgeMode(AcknowledgeMode.AUTO);
		container.afterPropertiesSet();
		container.start();
		return container;
	}

	public class TestListener implements ChannelAwareMessageListener {

		private final ThreadLocal<Integer> count = new ThreadLocal<Integer>();

		private final CountDownLatch latch;

		private final boolean fail;

		public TestListener(CountDownLatch latch, boolean fail) {
			this.latch = latch;
			this.fail = fail;
		}

		public void handleMessage(String value) {
		}

		@Override
		public void onMessage(Message message, Channel channel) throws Exception {
			String value = new String(message.getBody());
			try {
				logger.debug("Received: " + value);
				if (count.get() == null) {
					count.set(1);
				}
				else {
					count.set(count.get() + 1);
				}
				if (count.get() == txSize && fail) {
					logger.debug("Failing: " + value);
					count.set(0);
					throw new RuntimeException("Planned");
				}
			}
			finally {
				latch.countDown();
			}
		}
	}

}
