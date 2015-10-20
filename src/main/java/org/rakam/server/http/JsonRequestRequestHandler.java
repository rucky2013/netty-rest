package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Throwables;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static org.rakam.server.http.HttpServer.*;

public class JsonRequestRequestHandler implements HttpRequestHandler {
    private final ObjectMapper mapper;
    private final List<IRequestParameter> bodyParams;
    private final MethodHandle methodHandle;
    private final HttpService service;
    private final boolean isAsync;
    private final List<RequestPreprocessor<ObjectNode>> jsonPreprocessors;
    private final List<RequestPreprocessor<RakamHttpRequest>> requestPreprocessors;

    static final String bodyError;

    static {
        try {
            bodyError =  DEFAULT_MAPPER.writeValueAsString(errorMessage("Body must be an json object.", BAD_REQUEST));
        } catch (JsonProcessingException e) {
            throw Throwables.propagate(e);
        }
    }
    public JsonRequestRequestHandler(ObjectMapper mapper, ArrayList<IRequestParameter> bodyParams, MethodHandle methodHandle, HttpService service, List<RequestPreprocessor<ObjectNode>> jsonPreprocessors, List<RequestPreprocessor<RakamHttpRequest>> requestPreprocessors, boolean isAsync) {
        this.mapper = mapper;
        this.bodyParams = bodyParams;
        this.methodHandle = methodHandle;
        this.service = service;
        this.isAsync = isAsync;
        this.jsonPreprocessors = jsonPreprocessors;
        this.requestPreprocessors = requestPreprocessors;
    }

    @Override
    public void handle(RakamHttpRequest request) {
        request.bodyHandler(new Consumer<String>() {
            @Override
            public void accept(String body) {
                ObjectNode node;
                try {
                    // TODO: use custom deserialization to avoid the overhead of garbage generated by Jackson
                    node = (ObjectNode) mapper.readTree(body);
                } catch (ClassCastException e) {
                    request.response(bodyError, BAD_REQUEST).end();
                    return;
                } catch (UnrecognizedPropertyException e) {
                    returnError(request, "Unrecognized field: " + e.getPropertyName(), BAD_REQUEST);
                    return;
                } catch (InvalidFormatException e) {
                    returnError(request, format("Field value couldn't validated: %s ", e.getOriginalMessage()), BAD_REQUEST);
                    return;
                } catch (JsonMappingException e) {
                    returnError(request, e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), BAD_REQUEST);
                    return;
                } catch (JsonParseException e) {
                    returnError(request, format("Couldn't parse json: %s ", e.getOriginalMessage()), BAD_REQUEST);
                    return;
                } catch (IOException e) {
                    returnError(request, format("Error while mapping json: ", e.getMessage()), BAD_REQUEST);
                    return;
                }

                if(!jsonPreprocessors.isEmpty()) {
                    for (RequestPreprocessor<ObjectNode> preprocessor : jsonPreprocessors) {
                        if(!preprocessor.handle(request.headers(), node)) {
                            return;
                        }
                    }
                }

                if(!requestPreprocessors.isEmpty()) {
                    for (RequestPreprocessor<RakamHttpRequest> preprocessor : requestPreprocessors) {
                        if(!preprocessor.handle(request.headers(), request)) {
                            return;
                        }
                    }
                }

                Object[] values = new Object[bodyParams.size() + 1];
                values[0] = service;
                for (int i = 0; i < bodyParams.size(); i++) {
                    IRequestParameter param = bodyParams.get(0);
                    Object value = param.extract(node, request.headers());
                    if (param.required() && (value == null || value == NullNode.getInstance())) {
                        returnError(request, param.name() + " "+ param.in() + " parameter is required", BAD_REQUEST);
                        return;
                    }

                    values[i+1] = value;
                }

                Object invoke;
                try {
                    invoke = methodHandle.invokeWithArguments(values);
                } catch (Throwable e) {
                    requestError(e, request);
                    return;
                }

                handleRequest(mapper, isAsync, service, invoke, request);
            }
        });
    }
}
