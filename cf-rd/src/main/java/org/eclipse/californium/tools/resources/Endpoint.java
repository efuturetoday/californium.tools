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
import java.util.Stack;
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
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;

public class Endpoint extends CoapResource {

    private static final Logger LOGGER = Logger.getLogger(Endpoint.class.getCanonicalName());

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new Utils.DaemonThreadFactory("RDLifeTime#"));

    private int lifeTime = 86400;

    private String endpointName;
    private String domain;
    private String context;
    private String endpointType = "";
    private ScheduledFuture<?> ltExpiryFuture;

    public Endpoint(String endpointName, String domain)
            throws IllegalArgumentException {

        this(endpointName, domain, endpointName);
    }

    public Endpoint(String endpointName, String domain, String location)
            throws IllegalArgumentException {

        super(location);

        // Check length Restrictions
        int endpointNameLength = endpointName.getBytes(CoAP.UTF8_CHARSET).length;
        if (endpointNameLength > 63) {
            LOGGER.log(Level.WARNING, "Endpoint Name '{0}' too long ({1} bytes)",
                    new Object[]{endpointName, endpointNameLength});

            throw new IllegalArgumentException("Endpoint Name > 63 bytes");
        }
        int domainLength = endpointName.getBytes(CoAP.UTF8_CHARSET).length;
        if (domainLength > 63) {
            LOGGER.log(Level.WARNING, "Domain '{0}' too long ({1} bytes)",
                    new Object[]{endpointName, endpointNameLength});

            throw new IllegalArgumentException("Domain > 63 bytes");
        }

        this.endpointName = endpointName;
        this.domain = domain;
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
    6.6. Read Endpoint Links
        Some endpoints may wish to manage their links as a collection, and
        may need to read the current set of links in order to determine link
        maintenance operations.
    
        One or more links MAY be selected by using query filtering as
        specified in [RFC6690] Section 4.1
    
        The read request interface is specified as follows:
        Interaction: EP -> RD
        Method: GET
        URI Template: /{+location}{?href,rel,rt,if,ct}
        URI Template Variables:
    
        location := This is the Location path returned by the RD as a
        result of a successful earlier registration.
    
        href,rel,rt,if,ct := link relations and attributes specified in
        the query in order to select particular links based on their
        relations and attributes. "href" denotes the URI target of the
        link. See [RFC6690] Sec. 4.1
    
        The following responses codes are defined for this interface:
    
        Success: 2.05 "Content" or 200 "OK" upon success with an
        "application/link-format", "application/link-format+cbor", or
        "application/link-format+json" payload.
    
        Failure: 4.00 "Bad Request" or 400 "Bad Request". Malformed
        request.
    
        Failure: 4.04 "Not Found" or 404 "Not Found". Registration does not
        exist (e.g. may have expired).
    
        Failure: 5.03 "Service Unavailable" or 503 "Service Unavailable".
        Service could not perform the operation.
     */
    @Override
    public void handleGET(CoapExchange exchange) {

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
    
        Content-Format: application/link-format (mandatory)
        Content-Format: application/link-format+json (optional)
        Content-Format: application/link-format+cbor (optional)
    
        The following response codes are defined for this interface:
    
        Success: 2.04 "Changed" or 204 "No Content" if the update was
        successfully processed.
    
        Failure: 4.00 "Bad Request" or 400 "Bad Request". Malformed
        request.
    
        Failure: 4.04 "Not Found" or 404 "Not Found". Registration does not
        exist (e.g. may have expired).
    
        Failure: 5.03 "Service Unavailable" or 503 "Service Unavailable".
        Service could not perform the operation.
     */
    @Override
    public void handlePOST(CoapExchange exchange) {
        LOGGER.log(Level.INFO, "Updating Endpoint: {0}", this.getURI());

        try {
            this.setParameters(exchange.advanced().getRequest());
        } catch (IllegalArgumentException ex) {
            exchange.respond(ResponseCode.BAD_REQUEST, ex.getMessage());
            return;
        }

        // TODO: Add / Replace Resources
        // complete the request
        exchange.respond(ResponseCode.CHANGED);
    }

    /*
    6.5. Registration Removal
        Although RD entries have soft state and will eventually timeout after
        their lifetime, an endpoint SHOULD explicitly remove its entry from
        the RD if it knows it will no longer be available (for example on
        shut-down). This is accomplished using a removal interface on the RD
        by performing a DELETE on the endpoint resource.
    
        The removal request interface is specified as follows:
        Interaction: EP -> RD
        Method: DELETE
        URI Template: /{+location}
        URI Template Variables:
   
        location := This is the Location path returned by the RD as a
        result of a successful earlier registration.
    
        The following responses codes are defined for this interface:
    
        Success: 2.02 "Deleted" or 204 "No Content" upon successful deletion
    
        Failure: 4.00 "Bad Request" or 400 "Bad request". Malformed
        request.
    
        Failure: 4.04 "Not Found" or 404 "Not Found". Registration does not
        exist (e.g. may have expired).
    
        Failure: 5.03 "Service Unavailable" or 503 "Service Unavailable".
        Service could not perform the operation.
     */
    @Override
    public void handleDELETE(CoapExchange exchange) {
        this.delete();
        exchange.respond(ResponseCode.DELETED);
    }

    public void setParameters(Request request)
            throws IllegalArgumentException {

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
                throw new IllegalArgumentException("Lifetime has wrong NumberFormat (?lt)");
            }
        }

        // Lazy set Context or update by Query(con)
        if (this.context == null || queryContext != null) {
            try {
                this.setContextFromRequest(request, queryContext);
            } catch (URISyntaxException e) {
                LOGGER.log(Level.WARNING,
                        "Invalid context '{0}' from {1}:{2}  : {3}",
                        new Object[]{queryContext,
                            request.getSource().getHostAddress(),
                            request.getSourcePort(), e});

                throw new IllegalArgumentException("Context has invalid URISyntax (?con)");
            }
        }

        // Reset Lifetime counter
        this.setLifeTime(newLifeTime);

        this.updateEndpointResources(request.getPayloadString());
    }

    private void setContextFromRequest(Request request, String queryContext)
            throws URISyntaxException {

        String scheme;
        String host;
        int port;

        /* This parameter sets the scheme,
           address and port at which this server is available in the form
           scheme://host:port. */
        if (queryContext != null) {
            URI queryURI = new URI(queryContext);

            scheme = queryURI.getScheme();
            host = queryURI.getHost();
            port = queryURI.getPort();
        } else {
            /* In the absence of this parameter the
               scheme of the protocol, source IP address and source port of
               the register request are assumed. */

            scheme = request.getScheme();
            host = request.getSource().getHostAddress();
            port = request.getSourcePort();
        }

        // Set Context from gathered Values
        URI uri = new URI(scheme, null, host, port, null, null, null);
        this.context = uri.toString();
    }

    /*
    * add a new resource to the node. E.g. the resource temperature or
    * humidity. If the path is /readings/temp, temp will be a subResource
    * of readings, which is a subResource of the node.
     */
    public Resource addNodeResource(WebLink link)
            throws URISyntaxException {

        String path = link.getURI().substring(link.getURI().indexOf("/"));
        URI uri = new URI(path);

        Scanner scanner = new Scanner(uri.getPath());
        scanner.useDelimiter("/");

        // We start with this Instace as first Parent of the Tree
        Resource parent = this;

        Resource resource = null;
        String resourceName = "";
        boolean resourceExist;
        while (scanner.hasNext()) {
            resourceName = scanner.next();
            
            // Does the Resource already exists ?
            resourceExist = false;
            for (Resource res : parent.getChildren()) {
                if (res.getName().equals(resourceName)) {
                    resource = res;
                    resourceExist = true;
                    break;
                }
            }
            
            // ...If not just create it as invisble Resource
            if (!resourceExist) {
                resource = new CoapResource(resourceName, false);
                parent.add(resource);
            }
            
            parent = resource;
        }
        scanner.close();
        
        // At here the 'resource' holds the Endpoint Resource.
  
        return resource;
    }

    /**
     * Creates a new subResource for each resource the node wants register. Each
     * resource is separated by ",". E.g. A node can register a resource for
     * reading the temperature and another one for reading the humidity.
     */
    private boolean updateEndpointResources(String linkFormat) {

        Set<WebLink> links = LinkFormat.parse(linkFormat);

        for (WebLink link : links) {

            try {
                LOGGER.log(Level.INFO, "Trying to add Link: {0}...", link.getURI());
                Resource resource = addNodeResource(link);
                LOGGER.log(Level.INFO, "Resulting Link: {0}...", resource.getURI());

                // clear attributes to make registration idempotent
                for (String attribute : resource.getAttributes().getAttributeKeySet()) {
                    resource.getAttributes().clearAttribute(attribute);
                }

                // copy to resource list
                for (String attribute : link.getAttributes().getAttributeKeySet()) {
                    for (String value : link.getAttributes().getAttributeValues(attribute)) {
                        resource.getAttributes().addAttribute(attribute, value);
                    }
                }

                resource.getAttributes().setAttribute(LinkFormat.END_POINT, getEndpointName());

            } catch (URISyntaxException ex) {
                LOGGER.log(Level.WARNING, "Link: {0} is invalid. Skipping...", link.getURI());
            }
        }

        return true;
    }
    
    /*
    private Collection<Resource> getResources() {
        
    }*/
    
    /*
	 * the following three methods are used to print the right string to put in
	 * the payload to respond to the GET request.
     */
    public String toLinkFormat(List<String> query) {

        // Create new StringBuilder
        StringBuilder buffer = new StringBuilder();

        // Iterate Tree with a Stack(DFS)
        Stack<Resource> stack = new Stack<>();
        stack.push(this);
        while (!stack.isEmpty()) {
            Resource resource = stack.pop();
            stack.addAll(resource.getChildren());

            // Is the Resource added as Endpoint Resource?
            // If so it will have the END_POINT Attribute on it.
            if (resource.getAttributes().containsAttribute(LinkFormat.END_POINT)
                    && LinkFormat.matches(resource, query)) {
                // Build LinkFormat
                buffer.append("<")
                        .append(this.getContext())
                        .append(resource.getURI().substring(this.getURI().length()))
                        .append(">")
                        .append(LinkFormat.serializeAttributes(resource.getAttributes()))
                        .append(",");
            }
        }

        // Remove last delimiter
        if (buffer.length() > 0) {
            buffer.deleteCharAt(buffer.length() - 1);
        }

        return buffer.toString();
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

    public void setLifeTime() {
        this.setLifeTime(null);
    }

    public void setLifeTime(Integer newLifeTime) {
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

    class ExpiryTask extends TimerTask {

        Endpoint resource;

        public ExpiryTask(Endpoint resource) {
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
