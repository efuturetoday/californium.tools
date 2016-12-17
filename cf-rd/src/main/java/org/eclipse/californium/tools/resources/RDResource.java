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


import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;

public class RDResource extends CoapResource {

    public RDResource() {
        this("rd");
    }
    
    public RDResource(String resourceIdentifier) {
        super(resourceIdentifier);
        this.getAttributes().addResourceType("core.rd");
    }

    /*
	 * POSTs a new sub-resource to this resource. The name of the new
	 * sub-resource is a random number if not specified in the Option-query.
     */
    @Override
    public void handlePOST(CoapExchange exchange) {

        ResponseCode responseCode;

        LOGGER.info("Registration request from " + exchange.getSourceAddress().getHostName() + ":" + exchange.getSourcePort());

        // Parse Queries
        QueryList queryList = QueryList.parse(exchange.getRequestOptions().getUriQuery());
        
        // Get Endpoint Name(ep) and Domain(d) from Query
        String endpointName = queryList.get(LinkFormat.END_POINT);
        String domain = queryList.getOrDefault(LinkFormat.DOMAIN, "local");

        // Check for Mandatory Variables
        if (endpointName == null) {
            LOGGER.info("Missing Endpoint Name for " + exchange.getSourceAddress().getHostName() + ":" + exchange.getSourcePort());
            exchange.respond(ResponseCode.BAD_REQUEST, "Missing Endpoint Name (?ep)");
            return;
        }

        // Find Endpoint on this RD
        RDNodeResource resource = this.getEndpoint(endpointName, domain);
        
        // Check if Endpoint is already registered with this Directory
        if (resource == null) {
            // uncomment to use random resource names instead of registered Endpoint Name
            /*
			String randomName;
			do {
				randomName = Integer.toString((int) (Math.random() * 10000));
			} while (getChild(randomName) != null);
             */
            resource = new RDNodeResource(endpointName, domain);
            add(resource);

            responseCode = ResponseCode.CREATED;
        } else {
            responseCode = ResponseCode.CHANGED;
        }
        
        // set parameters of resource or abort on failure
        if (!resource.setParameters(exchange.advanced().getRequest())) {
            resource.delete(); // TODO? DELETE ?!
            exchange.respond(ResponseCode.BAD_REQUEST);
            return;
        }

        LOGGER.info("Adding new endpoint: " + resource.getContext());

        // inform client about the location of the new resource
        exchange.setLocationPath(resource.getURI());

        // complete the request
        exchange.respond(responseCode);
    }
    
    
    private RDNodeResource getEndpoint(String endpointName, String domain) {
        for (Resource child : this.getChildren()) {
            RDNodeResource childResource = (RDNodeResource) child;
            if (childResource.getEndpointName().equals(endpointName)
                    && childResource.getDomain().equals(domain)) {
                return childResource;
            }
        }

        return null;
    }

}
