/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.inference.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.index.IndexAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelConfig;
import org.elasticsearch.xpack.core.ml.inference.persistence.InferenceIndexConstants;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.core.ml.utils.ToXContentParams;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

public class TrainedModelProvider {

    private static final Logger logger = LogManager.getLogger(TrainedModelProvider.class);
    private final Client client;
    private final NamedXContentRegistry xContentRegistry;

    public TrainedModelProvider(Client client, NamedXContentRegistry xContentRegistry) {
        this.client = client;
        this.xContentRegistry = xContentRegistry;
    }

    public void storeTrainedModel(TrainedModelConfig trainedModelConfig, ActionListener<Boolean> listener) {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            XContentBuilder source = trainedModelConfig.toXContent(builder,
                new ToXContent.MapParams(Collections.singletonMap(ToXContentParams.FOR_INTERNAL_STORAGE, "true")));

            IndexRequest indexRequest = new IndexRequest(InferenceIndexConstants.LATEST_INDEX_NAME)
                .opType(DocWriteRequest.OpType.CREATE)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .id(trainedModelConfig.getModelId())
                .source(source);
            executeAsyncWithOrigin(client, ML_ORIGIN, IndexAction.INSTANCE, indexRequest,
                ActionListener.wrap(
                    r -> listener.onResponse(true),
                    e -> {
                        logger.error(new ParameterizedMessage(
                            "[{}] failed to store trained model for inference", trainedModelConfig.getModelId()), e);
                        if (ExceptionsHelper.unwrapCause(e) instanceof VersionConflictEngineException) {
                            listener.onFailure(new ResourceAlreadyExistsException(
                                Messages.getMessage(Messages.INFERENCE_TRAINED_MODEL_EXISTS, trainedModelConfig.getModelId())));
                        } else {
                            listener.onFailure(
                                new ElasticsearchStatusException(Messages.INFERENCE_FAILED_TO_STORE_MODEL,
                                    RestStatus.INTERNAL_SERVER_ERROR,
                                    e,
                                    trainedModelConfig.getModelId()));
                        }
                    }));
        } catch (IOException e) {
            // not expected to happen but for the sake of completeness
            listener.onFailure(new ElasticsearchParseException(
                Messages.getMessage(Messages.INFERENCE_FAILED_TO_SERIALIZE_MODEL, trainedModelConfig.getModelId()),
                e));
        }
    }

    public void getTrainedModel(String modelId, ActionListener<TrainedModelConfig> listener) {
        QueryBuilder queryBuilder = QueryBuilders.constantScoreQuery(QueryBuilders
            .idsQuery()
            .addIds(modelId));
        SearchRequest searchRequest = client.prepareSearch(InferenceIndexConstants.INDEX_PATTERN)
            .setQuery(queryBuilder)
            // use sort to get the last
            .addSort("_index", SortOrder.DESC)
            .setSize(1)
            .request();

        executeAsyncWithOrigin(client, ML_ORIGIN, SearchAction.INSTANCE, searchRequest,
            ActionListener.wrap(
                searchResponse -> {
                    if (searchResponse.getHits().getHits().length == 0) {
                        listener.onFailure(new ResourceNotFoundException(
                            Messages.getMessage(Messages.INFERENCE_NOT_FOUND, modelId)));
                        return;
                    }
                    BytesReference source = searchResponse.getHits().getHits()[0].getSourceRef();
                    parseInferenceDocLenientlyFromSource(source, modelId, listener);
                },
                listener::onFailure));
    }

    public void deleteTrainedModel(String modelId, ActionListener<Boolean> listener) {
        DeleteByQueryRequest request = new DeleteByQueryRequest().setAbortOnVersionConflict(false);

        request.indices(InferenceIndexConstants.INDEX_PATTERN);
        QueryBuilder query = QueryBuilders.termQuery(TrainedModelConfig.MODEL_ID.getPreferredName(), modelId);
        request.setQuery(query);
        request.setRefresh(true);

        executeAsyncWithOrigin(client, ML_ORIGIN, DeleteByQueryAction.INSTANCE, request, ActionListener.wrap(deleteResponse -> {
            if (deleteResponse.getDeleted() == 0) {
                listener.onFailure(new ResourceNotFoundException(
                    Messages.getMessage(Messages.INFERENCE_NOT_FOUND, modelId)));
                return;
            }
            listener.onResponse(true);
        }, e -> {
            if (e.getClass() == IndexNotFoundException.class) {
                listener.onFailure(new ResourceNotFoundException(
                    Messages.getMessage(Messages.INFERENCE_NOT_FOUND, modelId)));
            } else {
                listener.onFailure(e);
            }
        }));
    }

    private void parseInferenceDocLenientlyFromSource(BytesReference source,
                                                      String modelId,
                                                      ActionListener<TrainedModelConfig> modelListener) {
        try (InputStream stream = source.streamInput();
             XContentParser parser = XContentFactory.xContent(XContentType.JSON)
                 .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, stream)) {
            modelListener.onResponse(TrainedModelConfig.fromXContent(parser, true).build());
        } catch (Exception e) {
            logger.error(new ParameterizedMessage("[{}] failed to parse model", modelId), e);
            modelListener.onFailure(e);
        }
    }
}
