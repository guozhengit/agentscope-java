/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.examples.agui.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvents;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.agui.tools.ExampleTools;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * Configuration class that registers agents with the AG-UI registry.
 *
 * <p>This example demonstrates how to register multiple agents with different IDs.
 * Clients can select which agent to use via:
 * <ul>
 *   <li>URL path variable: {@code POST /agui/run/{agentId}}</li>
 *   <li>HTTP header: {@code X-Agent-Id: agentId}</li>
 *   <li>Request body: {@code forwardedProps.agentId}</li>
 * </ul>
 */
@Configuration
public class AgentConfiguration {

    @Bean
    public AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer() {
        AguiAgentRegistryCustomizer aguiAgentRegistryCustomizer =
                registry -> {
                    // Register a factory for the default agent
                    // Using a factory ensures each request gets a fresh agent instance
                    registry.registerFactory("default", this::createDefaultAgent);

                    // Register additional agents with different IDs
                    // Example: a simple chat agent without tools
                    registry.registerFactory("chat", this::createChatAgent);

                    // Example: an agent specialized for calculations
                    registry.registerFactory("calculator", this::createCalculatorAgent);
                };

        System.out.println("Registered agents with AG-UI registry: default, chat, calculator");
        System.out.println("Access agents via:");
        System.out.println("  - POST /agui/run (uses default-agent-id from config)");
        System.out.println("  - POST /agui/run/chat (uses 'chat' agent)");
        System.out.println("  - POST /agui/run with X-Agent-Id header");

        return aguiAgentRegistryCustomizer;
    }

    @Bean
    public AgentEventConverter exampleAgentEventConverter() {
        return new AgentEventConverter() {
            @Override
            public Set<Class<? extends AgentEvent>> eventTypes() {
                return Set.of(CustomEvent.class);
            }

            @Override
            public void convert(AgentEvent event, AguiStreamContext context) {
                CustomEvent customEvent = (CustomEvent) event;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("originalName", customEvent.getName());
                value.put("originalValue", customEvent.getValue());
                value.put("convertedBy", "exampleAgentEventConverter");
                context.emit(
                        new AguiEvent.Custom(
                                context.getThreadId(),
                                context.getRunId(),
                                "example_custom_event",
                                value));
            }
        };
    }

    @Bean
    public AguiEventEnricher exampleAguiEventEnricher() {
        return (source, events, context) -> {
            long timestamp = System.currentTimeMillis();
            return events.stream()
                    .map(event -> enrichExampleEvent(source, event, timestamp))
                    .toList();
        };
    }

    /**
     * Create the default agent instance.
     *
     * <p>This agent is configured with:
     * <ul>
     *   <li>OpenAI-compatible model (mikuapi.org, gpt-5.5) with streaming enabled</li>
     *   <li>Example tools (get_weather, calculate)</li>
     *   <li>In-memory conversation memory</li>
     *   <li>Retry/backoff hardened against the relay's intermittent 5xx errors</li>
     * </ul>
     */
    private Agent createDefaultAgent() {
        // Create toolkit with example tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());

        // Create the agent
        return ReActAgent.builder()
                .name("AG-UI Assistant")
                .sysPrompt(
                        "You are a helpful AI assistant exposed via the AG-UI protocol. "
                                + "You can help users with various tasks including weather queries "
                                + "and calculations. Be concise and helpful in your responses.")
                .model(buildMikuModel())
                .modelExecutionConfig(mikuExecutionConfig())
                .toolkit(toolkit)
                //                .middleware(exampleCustomEventMiddleware())
                .maxIters(10)
                .build();
    }

    /**
     * Create a simple chat agent without tools.
     *
     * <p>This agent is a pure conversational assistant.
     */
    private Agent createChatAgent() {
        return ReActAgent.builder()
                .name("Chat Assistant")
                .sysPrompt(
                        "You are a friendly conversational assistant. "
                                + "Engage in natural conversation and help users "
                                + "with general questions and discussions.")
                .model(buildMikuModel())
                .modelExecutionConfig(mikuExecutionConfig())
                .middleware(exampleCustomEventMiddleware())
                .maxIters(1)
                .build();
    }

    /**
     * Create a calculator agent specialized for mathematical operations.
     */
    private Agent createCalculatorAgent() {
        // Create toolkit with only calculation tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExampleTools());

        return ReActAgent.builder()
                .name("Calculator Agent")
                .sysPrompt(
                        "You are a mathematical assistant specialized in calculations. "
                                + "Use the calculate tool to perform mathematical operations. "
                                + "Always show your work and explain the results.")
                .model(buildMikuModel())
                .modelExecutionConfig(mikuExecutionConfig())
                .toolkit(toolkit)
                .middleware(exampleCustomEventMiddleware())
                .maxIters(5)
                .build();
    }

    private static AguiEvent enrichExampleEvent(
            AgentEvent source, AguiEvent event, long fallbackTimestamp) {
        Long timestamp = event.timestamp() != null ? event.timestamp() : fallbackTimestamp;
        Object rawEvent = event.rawEvent();
        if (rawEvent == null && source instanceof CustomEvent customEvent) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("agentEventType", source.getType().name());
            if (customEvent.getName() != null) {
                value.put("name", customEvent.getName());
            }
            rawEvent = value;
        }
        return AguiEvents.withBaseProperties(event, timestamp, rawEvent);
    }

    private static MiddlewareBase exampleCustomEventMiddleware() {
        return new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(
                    Agent agent,
                    RuntimeContext ctx,
                    AgentInput input,
                    Function<AgentInput, Flux<AgentEvent>> next) {
                CustomEvent event =
                        new CustomEvent("example_agent_event", exampleCustomEventValue(agent, ctx));
                return next.apply(input)
                        .concatMap(
                                agentEvent ->
                                        agentEvent instanceof AgentStartEvent
                                                ? Flux.just(agentEvent, event)
                                                : Flux.just(agentEvent));
            }
        };
    }

    private static Map<String, Object> exampleCustomEventValue(Agent agent, RuntimeContext ctx) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("agentName", agent.getName());
        if (ctx != null && ctx.getSessionId() != null) {
            value.put("sessionId", ctx.getSessionId());
        }
        return value;
    }

    /**
     * Build an {@link OpenAIChatModel} wired to the mikuapi.org OpenAI-compatible relay.
     *
     * <p>Configuration comes from environment variables:
     * <ul>
     *   <li>{@code MIKU_API_KEY}  - required, the relay API key</li>
     *   <li>{@code MIKU_BASE_URL} - optional, default {@code https://mikuapi.org}</li>
     *   <li>{@code MIKU_MODEL}    - optional, default {@code gpt-5.5}</li>
     * </ul>
     *
     * <p>The relay's upstream is intermittently flaky (occasional HTTP 502 / "Upstream
     * service temporarily unavailable"), so retry/backoff is hardened beyond the framework
     * default: up to 4 attempts with exponential backoff, retrying all errors (auth already
     * verified working, so no 4xx false-retry concern for this endpoint).
     */
    private OpenAIChatModel buildMikuModel() {
        String apiKey = System.getenv("MIKU_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "MIKU_API_KEY environment variable is required to use the mikuapi.org relay. "
                            + "Please set it before starting the application.");
        }
        String baseUrl = envOrDefault("MIKU_BASE_URL", "https://mikuapi.org");
        String modelName = envOrDefault("MIKU_MODEL", "gpt-5.5");

        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .generateOptions(
                        GenerateOptions.builder().executionConfig(mikuExecutionConfig()).build())
                .build();
    }

    /**
     * Hardened {@link ExecutionConfig} for the mikuapi.org relay.
     *
     * <p>The relay's upstream is intermittently flaky (occasional HTTP 502 / "Upstream service
     * temporarily unavailable"), so model calls are retried up to 4 attempts with exponential
     * backoff. {@code retryOn(error -> true)} retries all errors: authentication is already
     * verified working against this endpoint, so there is no 4xx false-retry concern.
     *
     * <p>This config is attached at the agent layer via {@code ReActAgent.Builder
     * .modelExecutionConfig(...)}. {@link ReActAgent#buildGenerateOptions()} merges it as the
     * <em>primary</em> execution config, which is what pins the retry budget at 4 attempts
     * instead of the framework default {@code ExecutionConfig.MODEL_DEFAULTS} (3 attempts).
     */
    private static ExecutionConfig mikuExecutionConfig() {
        return ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(3))
                .maxAttempts(4)
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(20))
                .backoffMultiplier(2.0)
                .retryOn(error -> true)
                .build();
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
