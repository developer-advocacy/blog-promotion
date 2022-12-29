package blogs;

import blogs.pipelines.AllPipelinesInitializedEvent;
import blogs.pipelines.PipelineInitializedEvent;
import com.joshlong.twitter.Twitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.core.GenericHandler;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.context.IntegrationFlowContext;
import org.springframework.integration.feed.dsl.Feed;
import org.springframework.integration.metadata.MetadataStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

@Slf4j
@Configuration
class IntegrationConfiguration {

	@Bean
	ApplicationListener<PipelineInitializedEvent> pipelineInitializedApplicationListener(
			Map<String, Pipeline> pipelines, ApplicationEventPublisher publisher) {
		var counter = new AtomicInteger();
		return event -> {
			counter.incrementAndGet();
			if (counter.get() == pipelines.size()) {
				log.debug("publishing " + AllPipelinesInitializedEvent.class.getSimpleName() + "!");
				publisher.publishEvent(new AllPipelinesInitializedEvent(pipelines.values().toArray(new Pipeline[0])));
			}
		};
	}

	@Bean
	DataSourceMetadataStore dataSourceMetadataStore(JdbcTemplate jdbcTemplate,
			TransactionTemplate transactionTemplate) {
		return new DataSourceMetadataStore(jdbcTemplate, transactionTemplate);
	}

	@Bean
	ApplicationListener<AllPipelinesInitializedEvent> promotionIntegrationFlowRunner(Twitter twitter,
			Twitter.Client client, Map<String, Pipeline> pipelines, IntegrationFlowContext ctx) {
		return event -> visitPipelinesAndLaunchIntegrationFlow(ctx, pipelines,
				(id, pipeline) -> buildPromotionIntegrationFlow(twitter, client, id, pipeline));
	}

	@Bean
	ApplicationListener<AllPipelinesInitializedEvent> feedIngestIntegrationFlowRunner(Map<String, Pipeline> pipelines,
			IntegrationFlowContext ctx, MetadataStore metadataStore) {
		return event -> visitPipelinesAndLaunchIntegrationFlow(ctx, pipelines,
				(id, pipeline) -> buildIngestIntegrationFlow(id, pipeline, metadataStore));
	}

	private static void visitPipelinesAndLaunchIntegrationFlow(IntegrationFlowContext context,
			Map<String, Pipeline> pipelines, BiFunction<String, Pipeline, IntegrationFlow> mapper) {
		pipelines //
				.forEach((id, blogPromotionPipeline) -> {
					log.info("the id [" + id + "] is mapped to [" + blogPromotionPipeline + "]");
					var flow = mapper.apply(id, blogPromotionPipeline);
					context.registration(flow).register().start();
				});
	}

	private static IntegrationFlow buildPromotionIntegrationFlow(Twitter twitter, Twitter.Client client, String id,
			Pipeline pipeline) {

		return IntegrationFlow//
				.from((MessageSource<PromotableBlog>) () -> {
					var promotable = pipeline.getPromotableBlogs();
					log.debug("there are " + promotable.size() + " " + PromotableBlog.class.getSimpleName()
							+ "s to promote for pipeline [" + id + "]");
					if (promotable.size() > 0) {
						return MessageBuilder.withPayload(promotable.get(0)).build();
					}

					return null;
				}, p -> p.poller(pc -> pc.fixedRate(1, TimeUnit.MINUTES)))//
				.filter(PromotableBlog.class,
						promotableBlog -> promotableBlog.blogPost().published()
								.isAfter(Instant.now().minus(Duration.ofDays(10))))//
				.handle((GenericHandler<PromotableBlog>) (payload, headers) -> {
					var tweet = pipeline.composeTweetFor(payload);
					var sent = twitter.scheduleTweet(client, new Date(), pipeline.getTwitterUsername(), tweet, null);
					if (Objects.equals(sent.block(), Boolean.TRUE)) {
						log.debug("sent a tweet for " + payload.blogPost().title());
						pipeline.promote(payload.blogPost());
					}
					return null;
				}) //
				.get();
	}

	private static IntegrationFlow buildIngestIntegrationFlow(String beanName, Pipeline promotionPipeline,
			MetadataStore metadataStore) {
		log.debug("launching " + IntegrationFlow.class.getName() + " for " + beanName + '.');
		var inbound = Feed //
				.inboundAdapter(promotionPipeline.getFeedUrl(), beanName) //
				.metadataStore(metadataStore);
		return IntegrationFlow //
				.from(inbound, p -> p.poller(pm -> pm.fixedRate(1, TimeUnit.SECONDS)))//
				.transform(promotionPipeline::mapBlogPost) //
				.transform(promotionPipeline::record) //
				.handle((GenericHandler<BlogPost>) (payload, headers) -> {
					if (log.isDebugEnabled()) {
						var url = payload.url();
						log.debug("ingested a blogPost [" + url + "]");
						headers.forEach((key, value) -> log.debug(url + ":" + key + '=' + value));
					}
					return null;
				}).get();
	}

}