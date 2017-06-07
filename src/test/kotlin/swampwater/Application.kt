package swampwater

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication.run
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.DependsOn
import org.springframework.context.support.ConversionServiceFactoryBean
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpHeaders.CONTENT_TYPE
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.integration.config.EnableIntegration
import org.springframework.integration.discord.common.DiscordGatewayContainer
import org.springframework.integration.discord.inbound.DiscordGatewayMessageProducer
import org.springframework.integration.discord.outbound.DiscordGatewayMessageHandler
import org.springframework.integration.discord.support.DiscordMessageHeaderAccessor
import org.springframework.integration.dsl.IntegrationFlow
import org.springframework.integration.dsl.IntegrationFlows.from
import org.springframework.integration.dsl.channel.MessageChannels.queue
import org.springframework.integration.dsl.http.Http
import org.springframework.integration.scheduling.PollerMetadata
import org.springframework.integration.scheduling.PollerMetadata.DEFAULT_POLLER
import org.springframework.messaging.MessageChannel
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler
import org.springframework.scheduling.support.PeriodicTrigger
import org.springframework.web.util.UriComponentsBuilder.fromUriString
import swampwater.discord.*
import org.springframework.integration.discord.support.RateLimitingInterceptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors.newSingleThreadScheduledExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream


@SpringBootApplication(scanBasePackages = arrayOf("org.springframework.integration.discord", "swampwater"))
@EnableIntegration
open class Application(
        builder: RestTemplateBuilder,
        val objectMapper: ObjectMapper,
        @Value("\${discord.baseUrl}") val baseUrl: String,
        @Value("Bot \${discord.authorization}") val authorization: String,
        @Value("\${discord.gateway.version}") version: String
) {

    private val restTemplate = builder
            .additionalInterceptors(ClientHttpRequestInterceptor { request, body, execution ->
                request.headers[AUTHORIZATION] = authorization
                execution.execute(request, body)
            }, RateLimitingInterceptor())
            .build()

    private val gatewayUrl: java.net.URI by lazy {
        fromUriString(restTemplate.getForObject("$baseUrl/gateway/bot", Gateway::class.java).url)
                .queryParam("encoding", "json")
                .queryParam("v", version)
                .build()
                .toUri()
    }

    @Bean
    open fun webSocketConversionService() = ConversionServiceFactoryBean()
            .apply {
                setConverters(mutableSetOf<Any>(
                        Converter<String, Dispatch> {
                            objectMapper.readValue(it, Dispatch::class.java)
                        },
                        Converter<Dispatch, String> {
                            objectMapper.writeValueAsString(it)
                        },
                        Converter<ByteBuffer, Dispatch> {
                            InflaterInputStream(ByteBufferBackedInputStream(it)).use { s -> objectMapper.readValue(s, Dispatch::class.java) }
                        },
                        Converter<Dispatch, ByteBuffer> {
                            val result = ByteArrayOutputStream()
                            DeflaterOutputStream(result).use { s -> objectMapper.writeValue(s, it) }
                            ByteBuffer.wrap(result.toByteArray())
                        }
                ))
            }

    @Bean
    open fun scheduler() = ConcurrentTaskScheduler(newSingleThreadScheduledExecutor())

    @Bean(DEFAULT_POLLER)
    open fun defaultPoller() = PollerMetadata().apply { trigger = PeriodicTrigger(10, MILLISECONDS) }

    @Bean
    @DependsOn("applicationContextProvider")
    open fun gatewayContainer() = DiscordGatewayContainer(gatewayUrl, authorization, scheduler())

    @Bean
    open fun outboundGatewayHandler() = DiscordGatewayMessageHandler(gatewayContainer())

    @Bean
    open fun inboundGatewayProducer() = DiscordGatewayMessageProducer().apply { gatewayContainer().eventHandler = this }

    @Bean
    open fun gatewayInboundFlow(): IntegrationFlow {
        return from(inboundGatewayProducer())
                .route<Map<String, Any>, String>(
                        {
                            val routeHint = (it[DiscordMessageHeaderAccessor.EventType] ?: it[DiscordMessageHeaderAccessor.Op])?.toString()?.toLowerCase()?.toCamelCase()
                            "discord.$routeHint.inbound"
                        },
                        {
                            it
                                    .subFlowMapping("discord.messageCreate.inbound", { sf ->
                                        sf
                                                .enrichHeaders { h -> h.headerExpression("discord-channel", "payload.channelId") }
                                                .channel("discord.messageCreate.inbound")
                                    })
                                    .resolutionRequired(false)
                                    .defaultOutputChannel("nullChannel")

                        })
                .get()
    }

    @Bean("discord.message.outbound")
    open fun discordMessageOutbound(): MessageChannel = queue().get()

    @Bean
    open fun messageOutboundFlow(): IntegrationFlow = from(discordMessageOutbound())
            .split()
            .transform(String::class.java, { CreateMessage(it) })
            .enrichHeaders(mutableMapOf(CONTENT_TYPE to APPLICATION_JSON_VALUE as Any))
            .handle(Http
                    .outboundChannelAdapter<CreateMessage>({ "$baseUrl/channels/${it.headers["discord-channel"]}/messages" }, restTemplate)
                    .get())
            .get()

    @Bean
    open fun statusUpdateFlow(): IntegrationFlow = from(Http
            .inboundGateway("/status")
            .requestPayloadType(SetStatusRequest::class.java)
            .requestMapping { it.methods(POST) }
            .get())
            .transform { it: SetStatusRequest -> GameStatusUpdate(it.idle, it.game) }
            .enrichHeaders(mutableMapOf(DiscordMessageHeaderAccessor.Op to Op.StatusUpdate as Any))
            .handle(outboundGatewayHandler())
            .get()

    @Bean
    open fun jokes(): List<Joke> = objectMapper.readValue(javaClass.getResourceAsStream("/jokes.json"), objectMapper.typeFactory.constructCollectionType(List::class.java, Joke::class.java))
}

fun main(vararg args: String) {
    run(Application::class.java, *args)
}
