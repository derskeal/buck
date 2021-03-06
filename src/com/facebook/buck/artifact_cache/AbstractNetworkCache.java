/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventDispatcher;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.slb.HttpService;
import com.facebook.buck.slb.NoHealthyServersException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;

public abstract class AbstractNetworkCache extends AbstractAsynchronousCache {

  private static final Logger LOG = Logger.get(AbstractNetworkCache.class);

  private final String repository;
  protected final String scheduleType;
  protected final HttpService fetchClient;
  protected final HttpService storeClient;
  private final ErrorReporter errorReporter;

  public AbstractNetworkCache(NetworkCacheArgs args) {
    super(
        args.getCacheName(),
        args.getCacheMode(),
        args.getCacheReadMode(),
        args.getHttpWriteExecutorService(),
        args.getHttpFetchExecutorService(),
        new NetworkEventListener(
            args.getBuckEventBus(), args.getCacheName(), new ErrorReporter(args)),
        args.getMaxStoreSizeBytes(),
        args.getProjectFilesystem());
    this.repository = args.getRepository();
    this.scheduleType = args.getScheduleType();
    this.fetchClient = args.getFetchClient();
    this.storeClient = args.getStoreClient();
    this.errorReporter = new ErrorReporter(args);
  }

  private static boolean isNoHealthyServersException(Throwable exception) {
    if (exception == null) {
      return false;
    } else if (exception instanceof NoHealthyServersException) {
      return true;
    } else {
      return isNoHealthyServersException(exception.getCause());
    }
  }

  protected String getRepository() {
    return repository;
  }

  private static class NetworkEventListener implements CacheEventListener {
    private final EventDispatcher dispatcher;
    private final String name;
    private final ErrorReporter errorReporter;

    private NetworkEventListener(
        EventDispatcher dispatcher, String name, ErrorReporter errorReporter) {
      this.dispatcher = dispatcher;
      this.name = name;
      this.errorReporter = errorReporter;
    }

    @Override
    public FetchEvents fetchScheduled(RuleKey ruleKey) {
      // TODO(cjhopman): Send a scheduled event this when fetch is actually async.

      HttpArtifactCacheEvent.Started startedEvent =
          HttpArtifactCacheEvent.newFetchStartedEvent(ruleKey);
      HttpArtifactCacheEvent.Finished.Builder eventBuilder =
          HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
      eventBuilder.getFetchBuilder().setRequestedRuleKey(ruleKey);
      return new FetchEvents() {
        @Override
        public void started() {
          dispatcher.post(startedEvent);
        }

        @Override
        public void finished(FetchResult fetchResult) {
          eventBuilder.setTarget(fetchResult.getBuildTarget());
          eventBuilder
              .getFetchBuilder()
              .setAssociatedRuleKeys(fetchResult.getAssociatedRuleKeys().orElse(ImmutableSet.of()))
              .setArtifactContentHash(fetchResult.getArtifactContentHash())
              .setArtifactSizeBytes(fetchResult.getArtifactSizeBytes())
              .setFetchResult(fetchResult.getCacheResult())
              .setResponseSizeBytes(fetchResult.getResponseSizeBytes());
          dispatcher.post(eventBuilder.build());
        }

        @Override
        public void failed(IOException e, String msg, CacheResult result) {
          if (isNoHealthyServersException(e)) {
            errorReporter.reportFailureToEventBus(
                "NoHealthyServers",
                String.format(
                    "\n"
                        + "Failed to fetch %s over %s:\n"
                        + "Buck encountered a critical network failure.\n"
                        + "Please check your network connection and retry."
                        + "\n",
                    ruleKey, name));
          } else {
            String key = String.format("store:%s", e.getClass().getSimpleName());
            errorReporter.reportFailure(e, key, msg);
          }
          eventBuilder.getFetchBuilder().setErrorMessage(msg).setFetchResult(result);
          dispatcher.post(eventBuilder.build());
        }
      };
    }

    @Override
    public StoreEvents storeScheduled(ArtifactInfo info, long artifactSizeBytes) {
      final HttpArtifactCacheEvent.Scheduled scheduled =
          HttpArtifactCacheEvent.newStoreScheduledEvent(
              ArtifactCacheEvent.getTarget(info.getMetadata()), info.getRuleKeys());
      dispatcher.post(scheduled);

      HttpArtifactCacheEvent.Started startedEvent =
          HttpArtifactCacheEvent.newStoreStartedEvent(scheduled);
      HttpArtifactCacheEvent.Finished.Builder finishedEventBuilder =
          HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
      return new StoreEvents() {
        @Override
        public void started() {
          dispatcher.post(startedEvent);
          finishedEventBuilder.getStoreBuilder().setRuleKeys(info.getRuleKeys());
          finishedEventBuilder
              .getStoreBuilder()
              .setArtifactSizeBytes(artifactSizeBytes)
              .setRuleKeys(info.getRuleKeys());
        }

        @Override
        public void finished(StoreResult result) {
          finishedEventBuilder
              .getStoreBuilder()
              .setArtifactContentHash(result.getArtifactContentHash())
              .setRequestSizeBytes(result.getRequestSizeBytes())
              .setWasStoreSuccessful(result.getWasStoreSuccessful());
          dispatcher.post(finishedEventBuilder.build());
        }

        @Override
        public void failed(IOException e, String errorMessage) {
          String key = String.format("store:%s", e.getClass().getSimpleName());
          errorReporter.reportFailure(e, key, errorMessage);
          finishedEventBuilder
              .getStoreBuilder()
              .setWasStoreSuccessful(false)
              .setErrorMessage(errorMessage);
          dispatcher.post(finishedEventBuilder.build());
        }
      };
    }
  }

  void reportFailureWithFormatKey(String format, Object... args) {
    errorReporter.reportFailure(format, String.format(format, args));
  }

  private static class ErrorReporter {
    private final EventDispatcher dispatcher;
    private final String errorTextTemplate;
    private final Set<String> seenErrors = Sets.newConcurrentHashSet();
    private final String name;

    public ErrorReporter(NetworkCacheArgs args) {
      dispatcher = args.getBuckEventBus();
      errorTextTemplate = args.getErrorTextTemplate();
      name = args.getCacheName();
    }

    private void reportFailure(String errorKey, String message) {
      LOG.warn(message);
      reportFailureToEventBus(errorKey, message);
    }

    private void reportFailure(Exception exception, String errorKey, String message) {
      LOG.warn(exception, message);
      reportFailureToEventBus(errorKey, message);
    }

    private void reportFailureToEventBus(String errorKey, String message) {
      if (seenErrors.add(errorKey)) {
        dispatcher.post(
            ConsoleEvent.warning(
                errorTextTemplate
                    .replaceAll("\\{cache_name}", Matcher.quoteReplacement(name))
                    .replaceAll("\\\\t", Matcher.quoteReplacement("\t"))
                    .replaceAll("\\\\n", Matcher.quoteReplacement("\n"))
                    .replaceAll("\\{error_message}", Matcher.quoteReplacement(message))));
      }
    }
  }

  @Override
  public void close() {
    fetchClient.close();
    storeClient.close();
  }
}
