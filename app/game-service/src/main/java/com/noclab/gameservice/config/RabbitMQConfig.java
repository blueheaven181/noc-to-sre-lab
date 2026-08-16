package com.noclab.gameservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "game.events";
    public static final String QUEUE = "session.completed.queue";
    public static final String ROUTING_KEY = "session.completed";

    @Bean
    public TopicExchange gameEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue sessionCompletedQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding sessionCompletedBinding(Queue sessionCompletedQueue, TopicExchange gameEventsExchange) {
        return BindingBuilder.bind(sessionCompletedQueue).to(gameEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
