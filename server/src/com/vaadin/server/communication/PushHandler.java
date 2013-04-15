/*
 * Copyright 2000-2013 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.server.communication;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.json.JSONException;

import com.vaadin.server.LegacyCommunicationManager.InvalidUIDLSecurityKeyException;
import com.vaadin.server.ServiceException;
import com.vaadin.server.SessionExpiredException;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServletRequest;
import com.vaadin.server.VaadinServletService;
import com.vaadin.server.VaadinSession;
import com.vaadin.shared.communication.PushMode;
import com.vaadin.ui.UI;

/**
 * Establishes bidirectional ("push") communication channels
 * 
 * @author Vaadin Ltd
 * @since 7.1
 */
public class PushHandler implements AtmosphereHandler {

    private static final String LONG_PADDING;
    static {
        char[] array = new char[4096];
        Arrays.fill(array, '-');
        LONG_PADDING = String.copyValueOf(array);

    }
    private VaadinServletService service;

    public PushHandler(VaadinServletService service) {
        this.service = service;
    }

    @Override
    public void onRequest(AtmosphereResource resource) {

        AtmosphereRequest req = resource.getRequest();
        VaadinServletRequest vaadinRequest = new VaadinServletRequest(req,
                service);

        VaadinSession session;
        try {
            session = service.findVaadinSession(vaadinRequest);
        } catch (ServiceException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return;
        } catch (SessionExpiredException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return;
        }

        session.lock();
        try {
            UI ui = service.findUI(vaadinRequest);
            if (ui == null) {
                // This should not happen
                getLogger().warning(
                        "Could not find the requested UI in session");
                return;
            }
            assert session.getPushMode() != PushMode.DISABLED;

            if (req.getMethod().equalsIgnoreCase("GET")) {
                /*
                 * We received a request to establish a push channel for a UI.
                 * Associate the AtmosphereResource with the UI and leave the
                 * connection open by calling resource.suspend(). If there is a
                 * pending push, send it now.
                 */
                getLogger().log(Level.FINER,
                        "New push connection with transport {0}",
                        resource.transport());
                resource.getResponse().setContentType(
                        "text/plain; charset=UTF-8");
                if (resource.transport() == TRANSPORT.STREAMING) {
                    // IE8 requires a longer padding to work properly if the
                    // initial message is small (#11573)
                    resource.padding(LONG_PADDING);
                }
                resource.suspend();

                AtmospherePushConnection connection = new AtmospherePushConnection(
                        ui);
                connection.connect(resource);

                ui.setPushConnection(connection);
            } else if (req.getMethod().equalsIgnoreCase("POST")) {
                AtmospherePushConnection connection = getConnectionForUI(ui);

                assert connection != null : "Got push from the client "
                        + "even though the connection does not seem to be "
                        + "valid. This might happen if a HttpSession is "
                        + "serialized and deserialized while the push "
                        + "connection is kept open or if the UI has a "
                        + "connection of unexpected type.";

                /*
                 * We received a UIDL request through Atmosphere. If the push
                 * channel is bidirectional (websockets), the request was sent
                 * via the same channel. Otherwise, the client used a separate
                 * AJAX request. Handle the request and send changed UI state
                 * via the push channel (we do not respond to the request
                 * directly.)
                 */
                try {
                    new ServerRpcHandler().handleRpc(ui, req.getReader(),
                            vaadinRequest);
                    connection.push(false);
                } catch (JSONException e) {
                    getLogger().log(Level.SEVERE,
                            "Error writing JSON to response", e);
                    // Refresh on client side
                    connection.sendMessage(VaadinService
                            .createCriticalNotificationJSON(null, null, null,
                                    null));
                } catch (InvalidUIDLSecurityKeyException e) {
                    getLogger().log(Level.WARNING,
                            "Invalid security key received from {0}",
                            resource.getRequest().getRemoteHost());
                    // Refresh on client side
                    connection.sendMessage(VaadinService
                            .createCriticalNotificationJSON(null, null, null,
                                    null));
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.INFO,
                    "An error occured while writing a push response", e);
        } finally {
            session.unlock();
        }
    }

    private static AtmospherePushConnection getConnectionForUI(UI ui) {
        PushConnection pushConnection = ui.getPushConnection();
        if (pushConnection instanceof AtmospherePushConnection) {
            AtmospherePushConnection apc = (AtmospherePushConnection) pushConnection;
            if (apc.isConnected()) {
                return apc;
            }
        }

        return null;
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        AtmosphereResource resource = event.getResource();

        String id = resource.uuid();
        if (event.isCancelled()) {
            // The client closed the connection.
            // TODO Do some cleanup
            getLogger().log(Level.FINER, "Connection closed for resource {0}",
                    id);
        } else if (event.isResuming()) {
            // A connection that was suspended earlier was resumed (committed to
            // the client.) Should only happen if the transport is JSONP or
            // long-polling.
            getLogger().log(Level.FINER, "Resuming request for resource {0}",
                    id);
        } else {
            // A message was broadcast to this resource and should be sent to
            // the client. We don't do any actual broadcasting, in the sense of
            // sending to multiple recipients; any UIDL message is specific to a
            // single client.
            getLogger().log(Level.FINER, "Writing message to resource {0}", id);

            Writer writer = resource.getResponse().getWriter();
            writer.write("for(;;);[{" + event.getMessage() + "}]");

            switch (resource.transport()) {
            case SSE:
            case WEBSOCKET:
                break;
            case STREAMING:
                writer.flush();
                break;
            case JSONP:
            case LONG_POLLING:
                resource.resume();
                break;
            default:
                getLogger().log(Level.SEVERE, "Unknown transport {0}",
                        resource.transport());
            }
        }
    }

    @Override
    public void destroy() {
    }

    private static final Logger getLogger() {
        return Logger.getLogger(PushHandler.class.getName());
    }
}
