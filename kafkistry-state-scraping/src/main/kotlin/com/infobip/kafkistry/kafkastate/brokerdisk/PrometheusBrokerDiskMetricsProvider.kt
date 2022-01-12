package com.infobip.kafkistry.kafkastate.brokerdisk

import okhttp3.OkHttpClient
import com.infobip.kafkistry.kafka.BrokerId
import com.infobip.kafkistry.kafka.ClusterBroker
import com.infobip.kafkistry.model.KafkaClusterIdentifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestClientResponseException

@Component
@ConfigurationProperties("app.kafka.metrics.prometheus")
class PrometheusBrokerDiskMetricsProperties {
    var enabled = false
    var timeOffset = 60
    var bulk = false
    lateinit var prometheusBaseUrl: String
    var totalPromQuery: String? = null
    var freePromQuery: String? = null
    lateinit var brokerLabelName: String
    var brokerLabelHostExtractPattern = "(.*)"
    var httpHeaders = mutableMapOf<String, String>()
}

@Component
@ConditionalOnProperty("app.kafka.metrics.prometheus.enabled")
class PrometheusBrokerDiskMetricsProvider(
    private val properties: PrometheusBrokerDiskMetricsProperties
) : BrokerDiskMetricsProvider {

    private val promUrl = "${properties.prometheusBaseUrl}/api/v1/query?query={query}&time={time}"

    private val restTemplate = RestTemplateBuilder()
        .additionalInterceptors(ClientHttpRequestInterceptor { request, body, execution ->
            request.headers.apply {
                properties.httpHeaders.forEach { (name, value) ->
                    add(name, value)
                }
            }
            execution.execute(request, body)
        })
        .requestFactory {
            OkHttp3ClientHttpRequestFactory(
                OkHttpClient.Builder().followRedirects(false).build()
            )
        }
        .errorHandler(object: ResponseErrorHandler {
            override fun hasError(response: ClientHttpResponse): Boolean = response.statusCode != HttpStatus.OK

            override fun handleError(response: ClientHttpResponse) {
                val responseBody = response.body.readAllBytes()
                throw RestClientResponseException(
                    "Prometheus API call failed: Http:${response.rawStatusCode} ${response.statusText}; " +
                            "Headers:${response.headers}; Body:${responseBody.decodeToString()}",
                    response.rawStatusCode, response.statusText, response.headers,
                    responseBody, null
                )
            }

        })
        .basicAuthentication("atomac", "LovelyDay-3222")
        .build()

    private lateinit var brokerPattern: Regex

    init {
        if (properties.bulk) {
            properties.brokerLabelName.length
            brokerPattern = Regex(properties.brokerLabelHostExtractPattern)
        }
    }

    override fun brokersDisk(
        clusterIdentifier: KafkaClusterIdentifier,
        brokers: List<ClusterBroker>
    ): Map<BrokerId, BrokerDiskMetric> {
        return if (properties.bulk) {
            val totalDisk = properties.totalPromQuery?.let { getBulkBrokersValues(it, brokers) }
            val freeDisk = properties.freePromQuery?.let { getBulkBrokersValues(it, brokers) }
            brokers.associate {
                it.brokerId to BrokerDiskMetric(total = totalDisk?.get(it.brokerId), free = freeDisk?.get(it.brokerId))
            }
        } else {
            brokers.associate { broker ->
                val totalDisk = properties.totalPromQuery?.let { getBrokerValue(it, broker) }
                val freeDisk = properties.freePromQuery?.let { getBrokerValue(it, broker) }
                broker.brokerId to BrokerDiskMetric(total = totalDisk, free = freeDisk)
            }
        }
    }

    private fun getBrokerValue(queryTemplate: String, broker: ClusterBroker): Long? {
        val promQuery = queryTemplate
            .replace("{brokerHost}", broker.host)
            .replace("{brokerId}", broker.brokerId.toString())
        val promResult = restTemplate.getForObject(
            promUrl, PrometheusResponse::class.java, mapOf("query" to promQuery, "time" to time())
        )
        return promResult?.data?.result.orEmpty().mapNotNull {
            it.value[1].toString().toLongOrNull()
        }.firstOrNull()
    }

    private fun String.applyBrokerPattern(): String? = brokerPattern.find(this)
        ?.let { if (it.groups.size >= 2) it.groupValues[1] else it.groupValues[0] }


    private fun getBulkBrokersValues(queryTemplate: String, brokers: List<ClusterBroker>): Map<BrokerId, Long> {
        val promQuery = queryTemplate
            .replace("{brokerHosts}", brokers.joinToString(separator = "|") { it.host })
            .replace("{brokerIds}", brokers.joinToString(separator = "|") { it.brokerId.toString() })
        val promResult = restTemplate.getForObject(
            promUrl, PrometheusResponse::class.java, mapOf("query" to promQuery, "time" to time())
        )
        return promResult?.data?.result.orEmpty().mapNotNull { promMetric ->
            val brokerLabel = promMetric.metric[properties.brokerLabelName] ?: return@mapNotNull null
            val brokerFromLabel = brokerLabel.applyBrokerPattern() ?: return@mapNotNull null
            val broker = brokers
                .find {
                    brokerFromLabel.equals(
                        it.host,
                        ignoreCase = true
                    ) || brokerFromLabel == it.brokerId.toString()
                }
                ?: brokers.find { it.host.applyBrokerPattern()?.equals(brokerFromLabel, ignoreCase = true) ?: false }
                ?: return@mapNotNull null
            val value = promMetric.value[1].toString().toLongOrNull() ?: return@mapNotNull null
            broker.brokerId to value
        }.toMap()
    }

    private fun time() = System.currentTimeMillis().div(1000).minus(properties.timeOffset).toString()

    private data class PrometheusResponse(
        val data: PrometheusData,
    )

    private data class PrometheusData(
        val result: List<PrometheusMetric>
    )

    private data class PrometheusMetric(
        val metric: Map<String, String>,
        val value: List<Any>,
    )

}
