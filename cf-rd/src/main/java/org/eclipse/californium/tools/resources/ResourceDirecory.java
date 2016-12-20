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

import java.util.logging.Level;
import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;

public class ResourceDirecory extends CoapResource {

    public ResourceDirecory() {
        this("rd");
    }

    public ResourceDirecory(String resourceIdentifier) {
        super(resourceIdentifier);
        this.getAttributes().addResourceType("core.rd");
    }
    
    /*
    6.3. Registration
    
        After discovering the location of an RD Function Set, an endpoint MAY
        register its resources using the registration interface. This
        interface accepts a POST from an endpoint containing the list of
        resources to be added to the directory as the message payload in the
        CoRE Link Format [RFC6690], JSON CoRE Link Format (application/link-
        format+json), or CBOR CoRE Link Format (application/link-format+cbor)
        [I-D.ietf-core-links-json], along with query string parameters
        indicating the name of the endpoint, its domain and the lifetime of
        the registration. All parameters except the endpoint name are
        optional. It is expected that other specifications will define
        further parameters (see Section 12.4). The RD then creates a new
        resource or updates an existing resource in the RD and returns its
        location. An endpoint MUST use that location when refreshing
        registrations using this interface. Endpoint resources in the RD are
        kept active for the period indicated by the lifetime parameter. The
        endpoint is responsible for refreshing the entry within this period
        using either the registration or update interface. The registration
        interface MUST be implemented to be idempotent, so that registering
        twice with the same endpoint parameter does not create multiple RD
        entries. A new registration may be created at any time to supercede
        an existing registration, replacing the registration parameters and
        links.
    
        The registration request interface is specified as follows:
        Interaction: EP -> RD
        Method: POST
        URI Template: /{+rd}{?ep,d,et,lt,con}
        URI Template Variables:
        rd := RD Function Set path (mandatory). This is the path of the
        RD Function Set, as obtained from discovery. An RD SHOULD use
        the value "rd" for this variable whenever possible.
    
        ep := Endpoint name (mandatory). The endpoint name is an
        identifier that MUST be unique within a domain. The maximum
        length of this parameter is 63 bytes.
    
        d := Domain (optional). The domain to which this endpoint
        belongs. The maximum length of this parameter is 63 bytes.
        When this parameter is elided, the RD MAY associate the
        endpoint with a configured default domain. The domain value is
        needed to export the endpoint to DNS-SD (see Section 10).
        et := Endpoint Type (optional). The semantic type of the
        endpoint. This parameter SHOULD be less than 63 bytes.
    
        lt := Lifetime (optional). Lifetime of the registration in
        seconds. Range of 60-4294967295. If no lifetime is included,
        a default value of 86400 (24 hours) SHOULD be assumed.
        con := Context (optional). This parameter sets the scheme,
        address and port at which this server is available in the form
        scheme://host:port. In the absence of this parameter the
        scheme of the protocol, source IP address and source port of
        the register request are assumed. This parameter is mandatory
        when the directory is filled by a third party such as an
        commissioning tool.
    
        Content-Format: application/link-format
        Content-Format: application/link-format+json
        Content-Format: application/link-format+cbor
    
        The following response codes are defined for this interface:
    
        Success: 2.01 "Created" or 201 "Created". The Location header MUST
        be included with the new resource entry for the endpoint. This
        Location MUST be a stable identifier generated by the RD as it is
        used for all subsequent operations on this registration. The
        resource returned in the Location is for the purpose of updating
        the lifetime of the registration and for maintaining the content
        of the registered links, including updating and deleting links.
    
        Failure: 4.00 "Bad Request" or 400 "Bad Request". Malformed
        request.
    
        Failure: 5.03 "Service Unavailable" or 503 "Service Unavailable".
        Service could not perform the operation.
    */
    @Override
    public void handlePOST(CoapExchange exchange) {

        ResponseCode responseCode;

        LOGGER.log(Level.INFO, "Registration request from {0}:{1}", new Object[]{exchange.getSourceAddress().getHostName(), exchange.getSourcePort()});

        // Parse Queries
        QueryList queryList = QueryList.parse(exchange.getRequestOptions().getUriQuery());

        // Get Endpoint Name(ep) and Domain(d) from Query
        String endpointName = queryList.get(LinkFormat.END_POINT);
        String domain = queryList.getOrDefault(LinkFormat.DOMAIN, "local");

        // Check for Mandatory Variables
        if (endpointName == null) {
            LOGGER.log(Level.INFO, "Missing Endpoint Name for {0}:{1}", new Object[]{exchange.getSourceAddress().getHostName(), exchange.getSourcePort()});
            exchange.respond(ResponseCode.BAD_REQUEST, "Missing Endpoint Name (?ep)");
            return;
        }

        // Find Endpoint on this RD
        Endpoint resource = this.getEndpoint(endpointName, domain);

        // Check if Endpoint is already registered with this Directory
        if (resource == null) {
            // uncomment to use random resource names instead of registered Endpoint Name
            /*
			String randomName;
			do {
				randomName = Integer.toString((int) (Math.random() * 10000));
			} while (getChild(randomName) != null);
             */
            try {
                resource = new Endpoint(endpointName, domain);
                responseCode = ResponseCode.CREATED;

            } catch (IllegalArgumentException ex) {
                exchange.respond(ResponseCode.BAD_REQUEST, ex.getMessage());
                return;
            }

        } else {
            responseCode = ResponseCode.CHANGED;
        }

        // set parameters of resource or abort on failure
        try {
            resource.setParameters(exchange.advanced().getRequest());
        } catch(IllegalArgumentException ex) {
            exchange.respond(ResponseCode.BAD_REQUEST, ex.getMessage());
            return;
        }

        LOGGER.log(Level.INFO, "Adding new Endpoint: {0}", resource.getURI());
        this.add(resource);
        
        // inform client about the location of the new resource
        exchange.setLocationPath(resource.getURI());

        // complete the request
        exchange.respond(responseCode);
    }

    private Endpoint getEndpoint(String endpointName, String domain) {
        for (Resource child : this.getChildren()) {
            Endpoint childResource = (Endpoint) child;
            if (childResource.getEndpointName().equals(endpointName)
                    && childResource.getDomain().equals(domain)) {
                return childResource;
            }
        }

        return null;
    }

}
