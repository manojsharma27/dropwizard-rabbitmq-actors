package io.appform.dropwizard.actors.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.rabbitmq.client.Channel;
import io.appform.dropwizard.actors.actor.ActorConfig;
import io.appform.dropwizard.actors.actor.MessageHandlingFunction;
import io.appform.dropwizard.actors.base.utils.NamingUtils;
import io.appform.dropwizard.actors.connectivity.RMQConnection;
import io.appform.dropwizard.actors.exceptionhandler.ExceptionHandlingFactory;
import io.appform.dropwizard.actors.exceptionhandler.handlers.ExceptionHandler;
import io.appform.dropwizard.actors.retry.RetryStrategy;
import io.appform.dropwizard.actors.retry.RetryStrategyFactory;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class UnmanagedConsumer<Message> {

    private final String name;
    private final String consumerTag;
    private final ActorConfig config;
    private final RMQConnection connection;
    private final ObjectMapper mapper;
    private final Class<? extends Message> clazz;
    private final int prefetchCount;
    private final MessageHandlingFunction<Message, Boolean> handlerFunction;
    private final MessageHandlingFunction<Message, Boolean> expiredMessageHandlingFunction;
    private final Function<Throwable, Boolean> errorCheckFunction;
    private final String queueName;
    private final RetryStrategy retryStrategy;
    private final ExceptionHandler exceptionHandler;

    private final List<Handler<Message>> handlers = Lists.newArrayList();

    public UnmanagedConsumer(final String name,
                             final ActorConfig config,
                             final RMQConnection connection,
                             final ObjectMapper mapper,
                             final RetryStrategyFactory retryStrategyFactory,
                             final ExceptionHandlingFactory exceptionHandlingFactory,
                             final Class<? extends Message> clazz,
                             final MessageHandlingFunction<Message, Boolean> handlerFunction,
                             final MessageHandlingFunction<Message, Boolean> expiredMessageHandlingFunction,
                             final Function<Throwable, Boolean> errorCheckFunction) {
        this(name, StringUtils.EMPTY, config, connection, mapper, retryStrategyFactory, exceptionHandlingFactory,
                clazz, handlerFunction, expiredMessageHandlingFunction, errorCheckFunction);
    }

    public UnmanagedConsumer(final String name,
                             final String consumerTag,
                             final ActorConfig config,
                             final RMQConnection connection,
                             final ObjectMapper mapper,
                             final RetryStrategyFactory retryStrategyFactory,
                             final ExceptionHandlingFactory exceptionHandlingFactory,
                             final Class<? extends Message> clazz,
                             final MessageHandlingFunction<Message, Boolean> handlerFunction,
                             final MessageHandlingFunction<Message, Boolean> expiredMessageHandlingFunction,
                             final Function<Throwable, Boolean> errorCheckFunction) {
        this.name = NamingUtils.prefixWithNamespace(name);
        this.consumerTag = consumerTag;
        this.config = config;
        this.connection = connection;
        this.mapper = mapper;
        this.clazz = clazz;
        this.prefetchCount = config.getPrefetchCount();
        this.handlerFunction = handlerFunction;
        this.expiredMessageHandlingFunction = expiredMessageHandlingFunction;
        this.errorCheckFunction = errorCheckFunction;
        this.queueName = NamingUtils.queueName(config.getPrefix(), name);
        this.retryStrategy = retryStrategyFactory.create(config.getRetryConfig());
        this.exceptionHandler = exceptionHandlingFactory.create(config.getExceptionHandlerConfig());
    }

    public void start() throws Exception {
        for (int i = 1; i <= config.getConcurrency(); i++) {
            Channel consumeChannel = connection.newChannel();
            final Handler<Message> handler =
                    new Handler<>(consumeChannel, mapper, clazz, prefetchCount, errorCheckFunction, retryStrategy,
                                  exceptionHandler, handlerFunction, expiredMessageHandlingFunction);
            String queueNameForConsumption;
            if (config.isSharded()) {
                queueNameForConsumption = NamingUtils.getShardedQueueName(queueName, i % config.getShardCount());
            } else {
                queueNameForConsumption = queueName;
            }

            final String tag = startConsumer(consumeChannel, queueNameForConsumption, handler, i);
            handler.setTag(tag);
            handlers.add(handler);
            log.info("Started consumer {} of type {} with tag {}", i, name, tag);
        }
    }

    public void stop() {
        handlers.forEach(handler -> {
            try {
                final Channel channel = handler.getChannel();
                if(channel.isOpen()) {
                    channel.basicCancel(handler.getTag());
                    //Wait till the handler completes consuming and ack'ing the current message.
                    log.info("Waiting for handler to complete processing the current message..");
                    while(handler.isRunning());
                    channel.close();
                    log.info("Consumer channel closed for [{}] with prefix [{}]", name, config.getPrefix());
                } else {
                    log.warn("Consumer channel already closed for [{}] with prefix [{}]", name, config.getPrefix());
                }
            } catch (Exception e) {
                log.error(String.format("Error closing consumer channel [%s] for [%s] with prefix [%s]", handler.getTag(), name, config.getPrefix()), e);
            }
        });
    }

    private String startConsumer(Channel consumeChannel, String queueNameForConsumption,
            Handler<Message> handler, int consumerIndex) throws IOException {

        if (StringUtils.isBlank(consumerTag)) {
            return consumeChannel.basicConsume(queueNameForConsumption, false, handler);
        }

        String generatedConsumerTag = NamingUtils.generateConsumerTag(consumerTag, consumerIndex);
        return consumeChannel.basicConsume(queueNameForConsumption, false, generatedConsumerTag, handler);
    }

}
