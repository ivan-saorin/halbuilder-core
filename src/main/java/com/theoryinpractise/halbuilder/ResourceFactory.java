package com.theoryinpractise.halbuilder;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import com.theoryinpractise.halbuilder.impl.ContentType;
import com.theoryinpractise.halbuilder.impl.api.Renderer;
import com.theoryinpractise.halbuilder.impl.json.JsonRenderer;
import com.theoryinpractise.halbuilder.impl.json.JsonResourceReader;
import com.theoryinpractise.halbuilder.impl.resources.MutableResource;
import com.theoryinpractise.halbuilder.impl.xml.XmlRenderer;
import com.theoryinpractise.halbuilder.impl.xml.XmlResourceReader;
import com.theoryinpractise.halbuilder.spi.Link;
import com.theoryinpractise.halbuilder.spi.ReadableResource;
import com.theoryinpractise.halbuilder.spi.Resource;
import com.theoryinpractise.halbuilder.spi.ResourceException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Future;

import static java.lang.String.format;

public class ResourceFactory {

    public static String HALXML = "application/hal+xml";
    public static String HALJSON = "application/hal+json";

    private Map<ContentType, Class<? extends Renderer>> contentRenderers = Maps.newHashMap();
    private TreeMap<String, String> namespaces = Maps.newTreeMap(Ordering.usingToString());
    private List<Link> links = Lists.newArrayList();
    private String baseHref;
    private AsyncHttpClient httpClient;

    public ResourceFactory() {
        this("http://localhost");
    }

    public ResourceFactory(String baseHref) {
        this.baseHref = baseHref;
        this.contentRenderers.put(new ContentType(HALXML), XmlRenderer.class);
        this.contentRenderers.put(new ContentType(HALJSON), JsonRenderer.class);
        this.httpClient = new AsyncHttpClient();
    }

    public String getBaseHref() {
        return baseHref;
    }

    public ResourceFactory withRenderer(String contentType, Class<? extends Renderer<String>> rendererClass) {
        contentRenderers.put(new ContentType(contentType), rendererClass);
        return this;
    }

    public ResourceFactory withNamespace(String namespace, String url) {
        if (namespaces.containsKey(namespace)) {
            throw new ResourceException(format("Duplicate namespace '%s' found for resource factory", namespace));
        }
        namespaces.put(namespace, url);
        return this;
    }

    public ResourceFactory withLink(String url, String rel) {
        links.add(new Link(url, rel));
        return this;
    }

    public Resource newHalResource(String href) {
        MutableResource resource = new MutableResource(this, href);

        // Add factory standard namespaces
        for (Map.Entry<String, String> entry : namespaces.entrySet()) {
            resource.withNamespace(entry.getKey(), entry.getValue());
        }

        // Add factory standard links
        for (Link link : links) {
            resource.withLink(link.getHref(), link.getRel(),
                    Optional.<Predicate<ReadableResource>>absent(), link.getName(), link.getTitle(), link.getHreflang());
        }

        return resource;
    }


    public ReadableResource newHalResource(Reader reader) {
        try {
            Reader bufferedReader = reader.markSupported() ? reader : new BufferedReader(reader);
            bufferedReader.mark(1);
            char firstChar = (char) bufferedReader.read();
            bufferedReader.reset();

            if (firstChar == '<') {
                return new XmlResourceReader(this).read(bufferedReader);
            } else if (firstChar == '{') {
                return new JsonResourceReader(this).read(bufferedReader);
            } else {
                throw new ResourceException("Unknown resource format");
            }
        } catch (Exception e) {
            throw new ResourceException(e);
        }
    }

    public Future<ReadableResource> newHalResource(URL url) {
        try {
            return httpClient.prepareGet(url.toExternalForm()).execute(new AsyncCompletionHandler<ReadableResource>() {
                @Override
                public ReadableResource onCompleted(Response response) throws Exception {
                    return newHalResource(new InputStreamReader(response.getResponseBodyAsStream()));
                }
            });
        } catch (Exception e) {
            throw new ResourceException(e);
        }
    }

    public Renderer<String> lookupRenderer(String contentType) {

        for (Map.Entry<ContentType, Class<? extends Renderer>> entry : contentRenderers.entrySet()) {
            if (entry.getKey().matches(contentType)) {
                try {
                    return entry.getValue().newInstance();
                } catch (InstantiationException e) {
                    throw new ResourceException(e);
                } catch (IllegalAccessException e) {
                    throw new ResourceException(e);
                }
            }

        }

        throw new IllegalArgumentException("Unsupported contentType: " + contentType);

    }


}
