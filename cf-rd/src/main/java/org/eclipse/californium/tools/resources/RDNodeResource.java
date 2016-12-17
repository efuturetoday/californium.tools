/**
 * *****************************************************************************
 * Copyright (c) 2015 Institute for Pervasive Computing, ETH Zurich and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *****************************************************************************
 */
package org.eclipse.californium.tools.resources;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.Utils;
import org.eclipse.californium.core.WebLink;
import org.eclipse.californium.core.coap.CoAP;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.OptionSet;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;

public class RDNodeResource extends CoapResource {

    private static final Logger LOGGER = Logger.getLogger(RDNodeResource.class.getCanonicalName());

    /*
	 * After the lifetime expires, the endpoint has RD_VALIDATION_TIMEOUT seconds
	 * to update its entry before the RD enforces validation and removes the endpoint
	 * if it does not respond.
     */
    private static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(//
            new Utils.DaemonThreadFactory("RDLifeTime#"));

    private int lifeTime = 86400;

    private String endpointName;
    private String domain;
    private String context;
    private String endpointType = "";
    private ScheduledFuture<?> ltExpiryFuture;

    public RDNodeResource(String ep, String domain) {
        super(ep);

        // check length restriction, but tolerantly accept
        int epLength = ep.getBytes(CoAP.UTF8_CHARSET).length;
        if (epLength > 63) {
            LOGGER.log(Level.WARNING, "Endpoint Name '{0}' too long ({1} bytes)",
                    new Object[]{ep, epLength});
        }

        this.endpointName = ep;
        this.domain = domain;
    }

    /*
    6.4. Registration Update
        The update interface is used by an endpoint to refresh or update its
        registration with an RD. To use the interface, the endpoint sends a
        POST request to the resource returned in the Location option in the
        response to the first registration.
    
        An update MAY update the lifetime or context registration parameters
        "lt", "con" as in Section 6.3 ) if they have changed since the last
        registration or update. Parameters that have not changed SHOULD NOT
        be included in an update. Adding parameters that have not changed
        increases the size of the message but does not have any other
        implications. Parameters MUST be included as query parameters in an
        update operation as in {registration}.
    
        Upon receiving an update request, an RD MUST reset the timeout for
        that endpoint and update the scheme, IP address and port of the
        endpoint, using the source address of the update, or the context
        ("con") parameter if present. If the lifetime parameter "lt" is
        included in the received update request, the RD MUST update the
        lifetime of the registration and set the timeout equal to the new
        lifetime.
    
        An update MAY optionally add or replace links for the endpoint by
        including those links in the payload of the update as a CoRE Link
        Format document. A link is replaced only if both the target URI and
        relation type match.
        In addition to the use of POST, as described in this section, there
        is an alternate way to add, replace, and delete links using PATCH as
        described in Section 6.7.
     */
    /**
     * Updates the endpoint parameters from POST and PUT requests.
     *
     * @param request A POST or PUT request with a {?et,lt,con} URI Template
     * query and a Link Format payload.
     *
     */
    public boolean setParameters(Request request) {

        // Parse Queries
        QueryList queryList = QueryList.parse(request.getOptions().getUriQuery());

        // Get LifeTime(lt) and Context(con) from Query
        String queryLifeTime = queryList.get(LinkFormat.LIFE_TIME);
        String queryContext = queryList.get(LinkFormat.CONTEXT);

        // Parse LifeTime when present in Query to Integer
        Integer newLifeTime = null;
        if (queryLifeTime != null) {
            try {
                newLifeTime = Integer.parseInt(queryLifeTime);
                if (lifeTime < 60) {
                    LOGGER.log(Level.INFO, "Enforcing minimal RD lifetime of 60 seconds (was {0})", this.lifeTime);
                    lifeTime = 60;
                }
                this.setLifeTime(newLifeTime);

            } catch (NumberFormatException ex) {
                // Wrong formatted Params
                return false;
            }
        }

        // Lazy set Context or update by Query(con)
        if (this.context == null || queryContext != null) {
            try {
                this.setContextFromRequest(request, queryContext);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Invalid context '{0}' from {1}:{2}  : {3}",
                        new Object[]{queryContext,
                            request.getSource().getHostAddress(),
                            request.getSourcePort(), e});

                return false;
            }
        }

        // Reset Lifetime counter
        this.setLifeTime(newLifeTime);

        return updateEndpointResources(request.getPayloadString());
    }

    private void setContextFromRequest(Request request, String queryContext)
            throws URISyntaxException {

        URI uri;
        String scheme;
        String host;
        Integer port;

        /* This parameter sets the scheme,
           address and port at which this server is available in the form
           scheme://host:port. */
        if (queryContext != null) {
            uri = new URI(queryContext);

            scheme = uri.getScheme();
            host = uri.getHost();
            port = uri.getPort();
        } else {
            /* In the absence of this parameter the
               scheme of the protocol, source IP address and source port of
               the register request are assumed. */
                
            scheme = request.getScheme();
            host = request.getSource().getHostAddress();
            port = request.getSourcePort();
            
            // Do we have no Scheme from Request ?		
            if (scheme == null || scheme.isEmpty()) {  // issue #38 & pr #42
                // Assume default Scheme
                scheme = CoAP.COAP_URI_SCHEME;
                
                // Do we have secure CoAP enabled ?
                OptionSet reqOptions = request.getOptions();
                if (reqOptions.hasUriPort()
                        && reqOptions.getUriPort() == CoAP.DEFAULT_COAP_SECURE_PORT) {
                    scheme = CoAP.COAP_SECURE_URI_SCHEME;
                }

                // Set the Request Scheme
                request.setScheme(scheme);
            }
        }

        // Set Context from gathered Values
        uri = new URI(scheme, null, host, port, null, null, null); // required to set port
        // CoAP context template: coap[s?]://<host>:<port>
        this.context = uri.toString();
    }

    /*
	 * add a new resource to the node. E.g. the resource temperature or
	 * humidity. If the path is /readings/temp, temp will be a subResource
	 * of readings, which is a subResource of the node.
     */
    public CoapResource addNodeResource(String path) {
        Scanner scanner = new Scanner(path);
        scanner.useDelimiter("/");
        String next = "";
        boolean resourceExist = false;
        Resource resource = this; // It's the resource that represents the endpoint

        CoapResource subResource = null;
        while (scanner.hasNext()) {
            resourceExist = false;
            next = scanner.next();
            for (Resource res : resource.getChildren()) {
                if (res.getName().equals(next)) {
                    subResource = (CoapResource) res;
                    resourceExist = true;
                }
            }
            if (!resourceExist) {
                subResource = new RDTagResource(next, true, this);
                resource.add(subResource);
            }
            resource = subResource;
        }
        subResource.setPath(resource.getPath());
        subResource.setName(next);
        scanner.close();
        return subResource;
    }

    @Override
    public void delete() {

        LOGGER.log(Level.INFO, "Removing endpoint: {0}", getContext());

        if (ltExpiryFuture != null) {
            // delete may be called from within the future
            ltExpiryFuture.cancel(false);
        }

        super.delete();
    }

    /*
	 * GET only debug return endpoint identifier
     */
    @Override
    public void handleGET(CoapExchange exchange) {
        exchange.respond(ResponseCode.FORBIDDEN, "RD update handle");
    }

    /*
	 * PUTs content to this resource. PUT is a periodic request from the
	 * node to update the lifetime.
     */
    @Override
    public void handlePOST(CoapExchange exchange) {
        LOGGER.log(Level.INFO, "Updating endpoint: {0}", getContext());
        
        // TODO CHANGE RETURN TYPE !
        if (!setParameters(exchange.advanced().getRequest())) {
            
        }

        // reset lifetime
        setLifeTime();

        // complete the request
        exchange.respond(ResponseCode.CHANGED);

    }

    /*
	 * DELETEs this node resource
     */
    @Override
    public void handleDELETE(CoapExchange exchange) {
        delete();
        exchange.respond(ResponseCode.DELETED);
    }

    public void setLifeTime() {
        this.setLifeTime(null);
    }
    
    public void setLifeTime(Integer newLifeTime ) {
        if (newLifeTime != null) {
            this.lifeTime = newLifeTime;
        }

        if (ltExpiryFuture != null) {
            ltExpiryFuture.cancel(true);
        }

        ltExpiryFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                delete();
            }
        }, this.lifeTime, TimeUnit.SECONDS);

    }

    /**
     * Creates a new subResource for each resource the node wants register. Each
     * resource is separated by ",". E.g. A node can register a resource for
     * reading the temperature and another one for reading the humidity.
     */
    private boolean updateEndpointResources(String linkFormat) {

        Set<WebLink> links = LinkFormat.parse(linkFormat);

        for (WebLink l : links) {

            CoapResource resource = addNodeResource(l.getURI().substring(l.getURI().indexOf("/")));

            // clear attributes to make registration idempotent
            for (String attribute : resource.getAttributes().getAttributeKeySet()) {
                resource.getAttributes().clearAttribute(attribute);
            }

            // copy to resource list
            for (String attribute : l.getAttributes().getAttributeKeySet()) {
                for (String value : l.getAttributes().getAttributeValues(attribute)) {
                    resource.getAttributes().addAttribute(attribute, value);
                }
            }

            resource.getAttributes().setAttribute(LinkFormat.END_POINT, getEndpointName());
        }

        return true;
    }

    /*
	 * the following three methods are used to print the right string to put in
	 * the payload to respond to the GET request.
     */
    public String toLinkFormat(List<String> query) {

        // Create new StringBuilder
        StringBuilder builder = new StringBuilder();

        // Build the link format
        buildLinkFormat(this, builder, query);

        // Remove last delimiter
        if (builder.length() > 0) {
            builder.deleteCharAt(builder.length() - 1);
        }

        return builder.toString();
    }

    private void buildLinkFormat(Resource resource, StringBuilder builder, List<String> query) {
        if (resource.getChildren().size() > 0) {

            // Loop over all sub-resources
            for (Resource res : resource.getChildren()) {
                if (LinkFormat.matches(res, query)) {
                    // Convert Resource to string representation
                    builder.append("<" + getContext());
                    builder.append(res.getURI().substring(this.getURI().length()));
                    builder.append(">");
                    builder.append(LinkFormat.serializeResource(res).toString().replaceFirst("<.+>", ""));
                }
                // Recurse
                buildLinkFormat(res, builder, query);
            }
        }
    }

    /*
	 * Setter And Getter
     */
    public String getEndpointName() {
        return endpointName;
    }

    public String getDomain() {
        return domain;
    }

    public String getEndpointType() {
        return endpointType == null ? "" : endpointType;
    }

    public void setEndpointType(String endpointType) {
        this.endpointType = endpointType;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    class ExpiryTask extends TimerTask {

        RDNodeResource resource;

        public ExpiryTask(RDNodeResource resource) {
            super();
            this.resource = resource;
        }

        @Override
        public void run() {
            LOGGER.log(Level.INFO, "Removing endpoint: {0} due end of life", this.resource.getEndpointName());
            this.resource.delete();
        }
    }

}
