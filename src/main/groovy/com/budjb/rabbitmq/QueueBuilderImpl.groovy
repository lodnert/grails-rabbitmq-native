/*
 * Copyright 2014-2015 Bud Byrd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.budjb.rabbitmq

import com.budjb.rabbitmq.connection.ConnectionContext
import com.budjb.rabbitmq.connection.ConnectionManager
import com.budjb.rabbitmq.exception.InvalidConfigurationException
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import grails.core.GrailsApplication
import org.apache.log4j.Logger
import org.codehaus.groovy.control.ConfigurationException
import org.springframework.beans.factory.annotation.Autowired

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * This class is based off of the queue builder present in the official Grails RabbitMQ plugin.
 */
class QueueBuilderImpl implements QueueBuilder {
    /**
     * Connection manager.
     */
    @Autowired
    ConnectionManager connectionManager

    /**
     * Grails application bean.
     */
    @Autowired
    GrailsApplication grailsApplication

    /**
     * Configure any defined exchanges and queues.
     */
    void configureQueues() {
        configureQueues(grailsApplication.config.rabbitmq.queues)

    }

    void configureQueues(Map config){
        QueueBuilderDelegate queueBuilderDelegate = new QueueBuilderDelegate()
        queueBuilderDelegate.configureFromMap(config)
    }

    void configureQueues(@DelegatesTo(QueueBuilderDelegate) Closure closure){
        // Create the queue builder
        QueueBuilderDelegate queueBuilderDelegate = new QueueBuilderDelegate()

        // Run the config
        closure.delegate = queueBuilderDelegate
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure()
    }

    /**
     * Class that does the work of building exchanges and queues.
     */
    private class QueueBuilderDelegate {
        /**
         * Logger
         */
        private static Logger log = Logger.getLogger(QueueBuilderDelegate)

        /**
         * Current exchange marker
         */
        private String currentExchange = null

        /**
         * Current connection to create exchanges/queues against
         */
        private ConnectionContext currentConnection = null

        private List<ExchangeBinding> exchangeBindings = []

        private Pattern namingPattern =  Pattern.compile(/(?<type>[-\w]+)_(?<name>[-\w]+)/)

        /**
         * RabbitMQ context bean
         */
        private RabbitContext rabbitContext = null

        void queue(String name, Map config){
            config.name = config.name?:name
            queue(config)
        }

        /**
         * Handles queue definitions
         *
         * @param method
         * @param args
         */
        void queue(Map parameters) {
            // Grab required parameters
            String name = parameters['name']
            String exchange = parameters['exchange']
            boolean autoDelete = Boolean.valueOf(parameters['autoDelete']?:false)
            boolean exclusive = Boolean.valueOf(parameters['exclusive']?:false)
            boolean durable = Boolean.valueOf(parameters['durable']?:false)
            Map arguments = (parameters['arguments'] instanceof Map) ? parameters['arguments'] : [:]

            // Ensure we have a name
            if (!parameters['name']) {
                throw new RuntimeException("name is required to declare a queue")
            }

            // Determine the connection
            ConnectionContext connection = currentConnection
            if (!currentConnection) {
                String connectionName = parameters['connection'] ?: null
                connection = getConnection(connectionName)
                if (!connection) {
                    if (!connectionName) {
                        throw new RuntimeException("no default connection found")
                    }
                    else {
                        throw new RuntimeException("no connection with name '${connectionName}' found")
                    }
                }
            }

            // Grab a channel
            Channel channel = connection.createChannel()

            // Declare the queue
            try {
                channel.queueDeclare(name, durable, exclusive, autoDelete, arguments)

                // If we are nested inside of an exchange definition, create
                // a binding between the queue and the exchange.
                if (currentExchange) {
                    bindQueue(parameters, currentExchange, channel)
                }
                else if (exchange) {
                    bindQueue(parameters, exchange, channel)
                }
            }
            finally {
                if (channel.isOpen()) {
                    channel.close()
                }
            }
        }

        /**
         * Binds a queue to an exchange.
         *
         * @param queue
         * @param exchange
         */
        void bindQueue(Map queue, String exchange, Channel channel) {
            if (queue['binding'] instanceof String) {
                channel.queueBind(queue['name'], exchange, queue['binding'])
            }
            else if (queue['binding'] instanceof Map) {
                if (!(queue['match'] in ['any', 'all'])) {
                    log.warn("skipping queue binding of queue \"${queue['name']}\" to headers exchange because the \"match\" property was not set or not one of (\"any\", \"all\")")
                    return
                }
                channel.queueBind(queue['name'], exchange, '', queue['binding'] + ['x-match': queue['match']])
            }
            else {
                channel.queueBind(queue['name'], exchange, '')
            }
        }

        void exchange(String name, Map parameters, @DelegatesTo(QueueBuilderDelegate) Closure closure = null) {
            parameters.name = parameters.name?:name
            exchange(parameters,closure)
        }

        /**
         * Defines a new exchange.
         *
         * @param args The properties of the exchange.
         * @param closure An optional closure that includes queue definitions that will be bound to this exchange.
         */
        void exchange(Map parameters, @DelegatesTo(QueueBuilderDelegate) Closure closure = null) {
            // Make sure we're not already in an exchange call
            if (currentExchange) {
                throw new RuntimeException("cannot declare an exchange within another exchange")
            }

            // Get parameters
            String name = parameters['name']
            String type = parameters['type']
            boolean autoDelete = Boolean.valueOf(parameters['autoDelete']?:false)
            boolean durable = Boolean.valueOf(parameters['durable']?:false)

            // Grab the extra arguments
            Map arguments = (parameters['arguments'] instanceof Map) ? parameters['arguments'] : [:]

            // Ensure we have a name
            if (!name) {
                throw new RuntimeException("an exchange name must be provided")
            }

            // Ensure we have a type
            if (!type) {
                throw new RuntimeException("a type must be provided for the exchange '${name}'")
            }

            // Determine the connection
            ConnectionContext connection = currentConnection
            if (!currentConnection) {
                String connectionName = parameters['connection'] ?: null
                connection = getConnection(connectionName)
                if (!connection) {
                    if (!connectionName) {
                        throw new RuntimeException("no default connection found")
                    }
                    else {
                        throw new RuntimeException("no connection with name '${connectionName}' found")
                    }
                }
            }

            // Grab a channel
            Channel channel = connection.createChannel()

            // Declare the exchange
            try {
                channel.exchangeDeclare(name, type, durable, autoDelete, arguments)
            }
            finally {
                if (channel.isOpen()) {
                    channel.close()
                }
            }

            Map<String,?> bindings = parameters.findAll {it.key.startsWith('bind-to_')}
            bindings.each{k,v ->
                Matcher matcher = namingPattern.matcher(k)
                if(matcher.matches()){

                    if(v instanceof Map) {

                        if (!v.binding) throw new InvalidConfigurationException("Exchange $name 'bind-to' parameter supplied but no binding provided")
                        switch (v.as) {
                            case 'source':
                                exchangeBindings += new ExchangeBinding(name, matcher.group('name'), v.binding, connection)
                                break
                            case 'destination': default:
                                exchangeBindings += new ExchangeBinding(matcher.group('name'), name, v.binding, connection)
                                break
                        }
                    }else if(v instanceof String){
                        exchangeBindings += new ExchangeBinding(matcher.group('name'), name, v, connection)
                    }else throw new InvalidConfigurationException("Exchange $name 'bind-to' parameter supplied but config is not map or string")
                }else{
                    throw new InvalidConfigurationException("Exchange $name 'bind-to' parameter supplied but $k does not match naming pattern")
                }
            }

            // Run the closure if given
            if (closure) {
                boolean resetConnection = (currentConnection == null)

                currentExchange = parameters['name']
                currentConnection = connection
                closure = closure.clone()
                closure.delegate = this
                closure()
                currentExchange = null

                if (resetConnection) {
                    currentConnection = null
                }
            }
        }

        /**
         * Lets the exchange and queue methods know what connection to build against.
         *
         * @param name
         * @param closure
         */
        void connection(String name, @DelegatesTo(QueueBuilderDelegate) Closure closure) {
            // Sanity check
            if (currentConnection != null) {
                throw new RuntimeException("unexpected connection in the queue configuration; there is a current connection already open")
            }

            // Find the connection
            ConnectionContext context = getConnection(name)
            if (!context) {
                throw new RuntimeException("no connection with name '${name}' found")
            }

            // Store the context
            currentConnection = context

            // Run the closure
            closure = closure.clone()
            closure.delegate = this
            closure()

            // Clear the context
            currentConnection = null
        }

        void configureFromMap(Map<String, Object> config){

            config.each{k,v ->
                Matcher matcher = namingPattern.matcher(k)

                if(matcher.matches()) {

                    switch(matcher.group('type')) {
                        case 'queue':
                            queue(matcher.group('name'), v)
                            break
                        case 'connection':
                            connection(matcher.group('name')){
                                configureFromMap(v.findAll{k2,v2 -> k2.startsWith('queue') || k2.startsWith('exchange')})
                            }
                            break
                        case 'exchange':
                            exchange(matcher.group('name'),v){
                                if(v.queues) configureFromMap(v.queues)
                                else configureFromMap(v.findAll{k2,v2 -> k2.startsWith('queue')})
                            }
                            break
                        default:
                            throw new InvalidConfigurationException("Queue Configuration key $k is not recognised")
                    }

                }else{
                    throw new InvalidConfigurationException("Queue Configuration key $k does not match pattern ${namingPattern.toString()}")
                }
            }
            setupExchangeBindings()

        }

        /**
         * This must be done after all exchanges have been configured otherwise there is the posssibility
         * the binding will fail if the exchange does not exist
         */
        void setupExchangeBindings(){
            exchangeBindings.each { binding ->
                // Grab a channel
                Channel channel = binding.connection.createChannel()

                // Declare the exchange
                try {
                   channel.exchangeBind(binding.destination, binding.source, binding.binding)
                }catch(Exception ex){
                    log.warn("Could not setup exchange binding $binding because ${ex.message}", ex)
                }
                finally {
                    if (channel.isOpen()) {
                        channel.close()
                    }
                }
            }
        }

        /**
         * Returns the name of the direct exchange type.
         *
         * @return
         */
        String getDirect() {
            return 'direct'
        }

        /**
         * Returns the name of the fanout exchange type.
         *
         * @return
         */
        String getFanout() {
            return 'fanout'
        }

        /**
         * Returns the name of the headers exchange type.
         *
         * @return
         */
        String getHeaders() {
            return 'headers'
        }

        /**
         * Returns the name of the topic exchange type.
         *
         * @return
         */
        String getTopic() {
            return 'topic'
        }

        /**
         * Returns the string representation of 'any', used in the match type for header exchanges.
         *
         * @return
         */
        String getAny() {
            return 'any'
        }

        /**
         * Returns the string representation of 'all', used in the match type for header exchanges.
         *
         * @return
         */
        String getAll() {
            return 'all'
        }

        /**
         * Creates any configured exchanges and/or queues.
         */
        private ConnectionContext getConnection(String name) {
            return QueueBuilderImpl.this.connectionManager.getContext(name)
        }
    }

    class ExchangeBinding{
        String source
        String destination
        String binding
        ConnectionContext connection

        ExchangeBinding(String source, String destination, String binding, ConnectionContext connection){
            this.source = source
            this.destination = destination
            this.binding = binding
            this.connection = connection
        }

        String toString(){
            "$source to $destination: $binding"
        }
    }
}
