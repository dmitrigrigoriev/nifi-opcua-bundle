/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stoecklin.processors;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;

import org.apache.nifi.components.PropertyDescriptor;

import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;

import org.apache.nifi.logging.ComponentLog;

import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import org.apache.nifi.reporting.InitializationException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.opcfoundation.ua.application.Client;
import org.opcfoundation.ua.application.SessionChannel;
import org.opcfoundation.ua.builtintypes.*;
import org.opcfoundation.ua.builtintypes.DataValue;
import org.opcfoundation.ua.common.ServiceResultException;
import org.opcfoundation.ua.core.*;
import org.opcfoundation.ua.transport.security.Cert;
import org.opcfoundation.ua.transport.security.KeyPair;
import org.opcfoundation.ua.transport.security.SecurityPolicy;
import org.opcfoundation.ua.utils.EndpointUtil;

import java.io.File;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.opcfoundation.ua.utils.EndpointUtil.selectBySecurityPolicy;


@Tags({"OPC", "OPCUA", "UA"})
@CapabilityDescription("Provides session management for OPC UA processors")
public class StandardOPCUAService extends AbstractControllerService implements OPCUAService {

    // Properties
    public static final PropertyDescriptor ENDPOINT = new PropertyDescriptor
            .Builder().name("Endpoint URL")
            .description("the opc.tcp address of the opc ua server")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor SERVER_CERT = new PropertyDescriptor
            .Builder().name("Certificate for Server application")
            .description("Certificate in .der format for server Nifi will connect, if left blank Nifi will attempt to retreive the certificate from the server")
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();
    public static final PropertyDescriptor SECURITY_POLICY = new PropertyDescriptor
            .Builder().name("Security Policy")
            .description("How should Nifi create the connection with the UA server")
            .required(true)
            .allowableValues("None", "Basic128Rsa15", "Basic256", "Basic256Rsa256")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor MESSAGE_SECURITY_MODE = new PropertyDescriptor
            .Builder().name("Security mode")
            .description("What security mode to use for connection to OPC UA Server. Valid only if \"Security Policy\" isn't \"None\"")
            .required(true)
            .allowableValues("None", "Sign", "SignAndEncrypt")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor AUTH_POLICY = new PropertyDescriptor
            .Builder().name("Authentication Policy")
            .description("How should Nifi authenticate with the UA server")
            .required(true)
            .defaultValue("Anon")
            .allowableValues("Anon", "Username")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor USERNAME = new PropertyDescriptor
            .Builder().name("User Name")
            .description("The user name to be used for the connection.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor PASSWORD = new PropertyDescriptor
            .Builder().name("Password")
            .description("The Password to be used for the connection")
            .required(false)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    public static final PropertyDescriptor APPLICATION_NAME = new PropertyDescriptor
            .Builder().name("Application Name")
            .description("The application name is used to label certificates identifying this application")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    private static final List<PropertyDescriptor> properties;
    // Global session variables used by all processors using an instance
    private Client opcClient = null;
    private SessionChannel currentSession = null;
    private EndpointDescription endpointDescription = null;
    private ActivateSessionResponse activateSessionResponse = null;
    private String userName = null;
    private String password = null;
    private String authType = null;
    private Pattern pattern = null;

    static {
        final List<PropertyDescriptor> props = new ArrayList<>();
        props.add(ENDPOINT);
        props.add(SECURITY_POLICY);
        props.add(MESSAGE_SECURITY_MODE);
        props.add(SERVER_CERT);
        props.add(AUTH_POLICY);
        props.add(USERNAME);
        props.add(PASSWORD);
        props.add(APPLICATION_NAME);
        properties = Collections.unmodifiableList(props);
    }

    private double timestamp;

    private String parseNodeTree(
            String print_indentation,
            int recursiveDepth,
            int max_recursiveDepth,
            ExpandedNodeId expandedNodeId,
            UnsignedInteger max_reference_per_node,
            ComponentLog logger) {


        StringBuilder stringBuilder = new StringBuilder();

        if (expandedNodeId == null) {
            return null;
        }

        if (recursiveDepth > max_recursiveDepth) {
            return null;
        }

        BrowseDescription[] NodesToBrowse = new BrowseDescription[1];
        NodesToBrowse[0] = new BrowseDescription();
        NodesToBrowse[0].setBrowseDirection(BrowseDirection.Forward);

        try {
            // Set node to browse to given Node
            if (expandedNodeId.getIdType() == IdType.String) {

                NodesToBrowse[0].setNodeId(new NodeId(expandedNodeId.getNamespaceIndex(), (String) expandedNodeId.getValue()));
            } else if (expandedNodeId.getIdType() == IdType.Numeric) {

                NodesToBrowse[0].setNodeId(new NodeId(expandedNodeId.getNamespaceIndex(), (UnsignedInteger) expandedNodeId.getValue()));
            } else if (expandedNodeId.getIdType() == IdType.Guid) {

                NodesToBrowse[0].setNodeId(new NodeId(expandedNodeId.getNamespaceIndex(), (UUID) expandedNodeId.getValue()));
            } else if (expandedNodeId.getIdType() == IdType.Opaque) {

                NodesToBrowse[0].setNodeId(new NodeId(expandedNodeId.getNamespaceIndex(), (byte[]) expandedNodeId.getValue()));
            } else {
                logger.error("OPCUAService: expandedNodeId has wrong type.");
                return null;
                // Return if no matches. Is this not a valid node?
            }
        } catch (Exception e) {
        }

        // Form request
        BrowseRequest browseRequest = new BrowseRequest();
        browseRequest.setNodesToBrowse(NodesToBrowse);

        // Form response, make request
        BrowseResponse browseResponse = new BrowseResponse();
        try {
            browseResponse = currentSession.Browse(browseRequest.getRequestHeader(), browseRequest.getView(), max_reference_per_node, browseRequest.getNodesToBrowse());
        } catch (Exception e) {
            logger.error("OPCUAService: failed to get browse response for " + browseRequest.getNodesToBrowse());
        }

        // Get results
        BrowseResult[] browseResults = browseResponse.getResults();

        // Retrieve reference descriptions for the result set
        // 0 index is assumed !!!
        ReferenceDescription[] referenceDesc = browseResults[0].getReferences();

        // Situation 1: There are no result descriptions because we have hit a leaf
        if (referenceDesc == null) {
            return null;
        }

        // Situation 2: There are results descriptions and each node must be parsed
        for (ReferenceDescription referenceDescription : referenceDesc) {

            // Print the current node
            Matcher matcher = pattern.matcher(referenceDescription.getNodeId().toString());
            if (matcher.find()) {
                // Print indentation
                if ("Yes".equals(print_indentation)) {
                    for ( int i = 0; i < recursiveDepth; i++ ) {
                        stringBuilder.append("- ");
                    }
                }
                stringBuilder.append(referenceDescription.getNodeId()).append(System.lineSeparator());
            }

            // Print the child node(s)
            String str = parseNodeTree(print_indentation, recursiveDepth + 1, max_recursiveDepth, referenceDescription.getNodeId(), max_reference_per_node, logger);
            if (str != null) {
                stringBuilder.append(str);
            }


        }

        return stringBuilder.toString();

        // we have exhausted the child nodes of the given node
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    /**
     * @param context the configuration context
     * @throws InitializationException if unable to create a database connection
     */
    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws InitializationException {

        final ComponentLog logger = getLogger();
        logger.info("StandardOPCUAService.onEnabled(): Creating variables");
        EndpointDescription[] endpointDescriptions = null;
        KeyPair myClientApplicationInstanceCertificate = null;
        KeyPair myHttpsCertificate = null;

        if (userName == null){
            userName = context.getProperty(USERNAME).getValue();
        }
        if (password == null){
            password = context.getProperty(PASSWORD).getValue();
        }
        if (authType == null){
            authType = context.getProperty(AUTH_POLICY).getValue();
        }

        // Initialize OPC UA Client

        // Load Client's certificates from file or create new certs
        logger.debug("StandardOPCUAService.onEnabled(): Creating Certificates");

        if (context.getProperty(SECURITY_POLICY).getValue().equals("None")) {
            // Build OPC Client
            logger.info("StandardOPCUAService.onEnabled(): No Security Policy requested");
            myClientApplicationInstanceCertificate = null;

        } else {

            myHttpsCertificate = Utils.getHttpsCert(context.getProperty(APPLICATION_NAME).getValue());

            // Load or create HTTP and Client's Application Instance Certificate and key
            switch (context.getProperty(SECURITY_POLICY).getValue()) {
                case "Basic128Rsa15": {
                    myClientApplicationInstanceCertificate = Utils.getCert(context.getProperty(APPLICATION_NAME).getValue(), SecurityPolicy.BASIC128RSA15);
                    break;

                }
                case "Basic256": {
                    myClientApplicationInstanceCertificate = Utils.getCert(context.getProperty(APPLICATION_NAME).getValue(), SecurityPolicy.BASIC256);
                    break;

                }
                case "Basic256Rsa256": {
                    myClientApplicationInstanceCertificate = Utils.getCert(context.getProperty(APPLICATION_NAME).getValue(), SecurityPolicy.BASIC256SHA256);
                    break;
                }
                default: {
                    myClientApplicationInstanceCertificate = null;
                    break;
                }
            }
        }

        logger.info("StandardOPCUAService.onEnabled(): Creating Client");

        // Create Client
        opcClient = Client.createClientApplication(myClientApplicationInstanceCertificate);
        opcClient.setTimeout(1000*60*20); // set timeout 10 minutes
        opcClient.getApplication().getHttpsSettings().setKeyPair(myHttpsCertificate);
        opcClient.getApplication().addLocale(Locale.ENGLISH);
        opcClient.getApplication().setApplicationName(new LocalizedText(context.getProperty(APPLICATION_NAME).getValue(), Locale.ENGLISH));
        opcClient.getApplication().setProductUri("urn:" + context.getProperty(APPLICATION_NAME).getValue());

        MessageSecurityMode mode = MessageSecurityMode.None;
        switch ( context.getProperty(MESSAGE_SECURITY_MODE).getValue() ) {
            case "Sign":
                mode = MessageSecurityMode.Sign;
                break;
            case "SignAndEncrypt":
                mode = MessageSecurityMode.SignAndEncrypt;
                break;
        }

        // if a certificate is provided
        if (context.getProperty(SERVER_CERT).getValue() != null) {
            Cert myOwnCert = null;

            // if a certificate is provided
            try {
                logger.error("StandardOPCUAService.onEnabled(): Certificate Provided...getting " + context.getProperty(SERVER_CERT).getValue());
                File myCertFile = new File(context.getProperty(SERVER_CERT).getValue());
                myOwnCert = Cert.load(myCertFile);

            } catch (Exception e1) {
                logger.error("StandardOPCUAService.onEnabled(): Error loading certificate " + e1.getMessage());
            }

            // Describe end point
            endpointDescription = new EndpointDescription();
            endpointDescription.setEndpointUrl(context.getProperty(ENDPOINT).getValue());
            endpointDescription.setServerCertificate(ByteString.valueOf(myOwnCert.getEncoded()));
            endpointDescription.setSecurityMode(mode);

            switch (context.getProperty(SECURITY_POLICY).getValue()) {
                case "Basic128Rsa15": {
                    endpointDescription.setSecurityPolicyUri(SecurityPolicy.BASIC128RSA15.getPolicyUri());
                    break;
                }
                case "Basic256": {
                    endpointDescription.setSecurityPolicyUri(SecurityPolicy.BASIC256.getPolicyUri());
                    break;
                }
                case "Basic256Rsa256": {
                    endpointDescription.setSecurityPolicyUri(SecurityPolicy.BASIC256SHA256.getPolicyUri());
                    break;
                }
                default: {
                    endpointDescription.setSecurityPolicyUri(SecurityPolicy.NONE.getPolicyUri());
                    logger.info("StandardOPCUAService.onEnabled(): No security mode specified");
                    break;
                }
            }

        } else {
            try {
                logger.info("StandardOPCUAService.onEnabled(): Discovering endpoints from " + context.getProperty(ENDPOINT).getValue());
                endpointDescriptions = opcClient.discoverEndpoints(context.getProperty(ENDPOINT).getValue());
                if (endpointDescriptions == null) {
                    logger.error("StandardOPCUAService.onEnabled(): Endpoint descriptions not received.");
                    return;
                }
            } catch (ServiceResultException e1) {

                logger.error("StandardOPCUAService.onEnabled(): Issue getting service endpoint descriptions: " + e1.getMessage());
            }
            switch (context.getProperty(SECURITY_POLICY).getValue()) {

                case "Basic128Rsa15": {
                    endpointDescriptions = selectBySecurityPolicy(endpointDescriptions, SecurityPolicy.BASIC128RSA15);
                    break;
                }
                case "Basic256": {
                    endpointDescriptions = selectBySecurityPolicy(endpointDescriptions, SecurityPolicy.BASIC256);
                    break;
                }
                case "Basic256Rsa256": {
                    endpointDescriptions = selectBySecurityPolicy(endpointDescriptions, SecurityPolicy.BASIC256SHA256);
                    break;
                }
                default: {
                    endpointDescriptions = selectBySecurityPolicy(endpointDescriptions, SecurityPolicy.NONE);
                    logger.info("StandardOPCUAService.onEnabled(): No security mode specified");
                    break;
                }
            }

            logger.info("StandardOPCUAService.onEnabled(): Count of Endpoints: " + endpointDescriptions.length);
            for (EndpointDescription description : endpointDescriptions) {
                logger.info("StandardOPCUAService.onEnabled(): Endpoint: " + description.toString());
            }

            // set the provided end point url to match the given one ( for local host problem )
            endpointDescription = EndpointUtil.selectByMessageSecurityMode(endpointDescriptions, mode)[0];
            endpointDescription.setEndpointUrl(context.getProperty(ENDPOINT).getValue());
        }

        logger.info("StandardOPCUAService.onEnabled(): Initialization Complete");

        // Create and activate session

        logger.debug("StandardOPCUAService.onEnabled(): Using endpoint: " + endpointDescription.toString());

        try {
            currentSession = opcClient.createSessionChannel(endpointDescription);
            String authType = context.getProperty(AUTH_POLICY).getValue();
            activateSession(authType, context.getProperty(USERNAME).getValue(), context.getProperty(PASSWORD).getValue());
            timestamp = System.currentTimeMillis();
        } catch (ServiceResultException e) {
            logger.error("StandardOPCUAService.onEnabled(): Error while creating initial SessionChannel: " + e.getMessage());
        }

        logger.info("StandardOPCUAService.onEnabled(): OPC UA client session ready");
    }

    private void activateSession(String authPolicy, String userName, String password) throws ServiceResultException {

        if (authPolicy.equals("Anon")) {
            activateSessionResponse = currentSession.activate();
        } else {
            activateSessionResponse = currentSession.activate(userName, password);
        }
    }

    public boolean updateSession() {

        final ComponentLog logger = getLogger();
        double elapsedTime = System.currentTimeMillis() - timestamp;
        if (elapsedTime < 0) {
            logger.info("StandardOPCUAService.updateSession(): not a valid timestamp");
            return false;
        }
        if ((elapsedTime) < currentSession.getSession().getSessionTimeout()) {
            logger.info("StandardOPCUAService.updateSession(): using current session");
            timestamp = System.currentTimeMillis();
            try {
                activateSession(authType, userName, password);
            } catch (ServiceResultException e) {
                logger.error("StandardOPCUAService.updateSession(): issue updating session " + e.getMessage());
            }
            return true;

        } else {
            try {

                logger.info("StandardOPCUAService.updateSession(): Create new session. EndpointDescription: "+endpointDescription);
                currentSession.close();
                currentSession = null;

                currentSession = opcClient.createSessionChannel(endpointDescription);

                activateSession(authType, userName, password);

                timestamp = System.currentTimeMillis();

                logger.info("StandardOPCUAService.updateSession(): New Session is created");

                return true;

            } catch (ServiceResultException e) {
                logger.error("StandardOPCUAService.updateSession(): Error by creating new session: " + e.getMessage());
                return false;
            }
        }
    }

    @OnDisabled
    public void shutdown() {
        // Close the session
        final ComponentLog logger = getLogger();

        try {
            if (currentSession != null) {
				currentSession.close();
			}
        } catch (Exception e) {
            logger.error("StandardOPCUAService.shutdown(): Error by shutting down: " + e.getMessage());
        }
    }

    @Override
    public byte[] getValue(List<String> reqTagnames, String returnTimestamp, String excludeNullValue, String nullValueString, String dataFormat, boolean longTimestamp, String deviceType, String deviceName) throws ProcessException {
        final ComponentLog logger = getLogger();

        //Create the nodes to read array
        ReadValueId nodesToRead[] = new ReadValueId[reqTagnames.size()];

        for (int i = 0; i < reqTagnames.size(); i++) {
            try {
                nodesToRead[i] = (new ReadValueId(NodeId.parseNodeId(reqTagnames.get(i)), Attributes.Value, null, null));
            } catch (Exception ex) {
                logger.warn("StandardOPCUAService.getValue(): error reading nodeId for" + reqTagnames.get(i));
            }
        }

        String serverResponse = "";

        // Form OPC request
        ReadRequest req = new ReadRequest();
        req.setMaxAge(500.00);
        req.setTimestampsToReturn(TimestampsToReturn.Both);
        req.setRequestHeader(null);
        req.setNodesToRead(nodesToRead);

        // Submit OPC Read and handle response
        try {
            ReadResponse readResponse = currentSession.Read(req);

            org.opcfoundation.ua.builtintypes.DataValue[] values;
            values = readResponse.getResults();

            // Validate response
            if (values != null) {
                if (values.length == 0) {
                    logger.error("StandardOPCUAService.getValue(): No value returned.");
                } else {
                    // Build Response according to Data Format
                    switch (dataFormat) {
                        case "CSV" :
                            serverResponse = getDataInCSV(nodesToRead, values, returnTimestamp, excludeNullValue, nullValueString, longTimestamp);
                            serverResponse.trim();
                            break;
                        case "TEMPUS" :
                            boolean isGateway = deviceType.equals("Gateway");
                            serverResponse = getDataInTempus(nodesToRead, values, returnTimestamp, excludeNullValue, nullValueString, longTimestamp, isGateway, deviceName);
                            break;
                    }
                }
            }

        } catch (Exception e) {
            logger.error("StandardOPCUAService.getValue(): Error parsing OPC Server Results: " + e.getMessage() + Arrays.toString(e.getStackTrace()));
        }

        return serverResponse.getBytes();
    }

    @Override
    public String getNameSpace(String print_indentation, int max_recursiveDepth, Pattern pattern, UnsignedInteger max_reference_per_node) throws ProcessException {

        final ComponentLog logger = getLogger();
        StringBuilder stringBuilder = new StringBuilder();
        ExpandedNodeId expandedNodeId = new ExpandedNodeId((Identifiers.RootFolder));
        this.pattern = pattern;

        stringBuilder.append(parseNodeTree(print_indentation, 0, max_recursiveDepth, expandedNodeId, max_reference_per_node, logger));
        return stringBuilder.toString();
    }

    @Override
    public String getNameSpace(String print_indentation, int max_recursiveDepth, ExpandedNodeId nodeId, Pattern pattern, UnsignedInteger max_reference_per_node) throws ProcessException {
        final ComponentLog logger = getLogger();
        StringBuilder stringBuilder = new StringBuilder();
        this.pattern = pattern;

        stringBuilder.append(parseNodeTree(print_indentation, 0, max_recursiveDepth, nodeId, max_reference_per_node, logger));
        return stringBuilder.toString();
    }

    private String getDataInCSV(ReadValueId nodesToRead[], DataValue values[], String returnTimestamp, String excludeNullValue, String nullValueString, boolean longTimestamp) {
        String serverResponse = "";
        for (int i = 0; i < values.length; i++) {
            String valueLine = "";
            try {
                // Build flowfile line
                if (excludeNullValue.equals("true") && values[i].getValue().toString().equals(nullValueString)) {
                    getLogger().debug("StandardOPCUAService.getDataInCSV(): Null value returned for " + values[i].getValue().toString() + " -- Skipping because property is set");
                    continue;
                }

                valueLine += nodesToRead[i].getNodeId().toString() + ",";
                valueLine += getTimeStamp(values[i], returnTimestamp, longTimestamp) + ",";
                valueLine += values[i].getValue().toString() + ","
                          + values[i].getStatusCode().getValue().toString()
                          + System.getProperty("line.separator");

            } catch (Exception ex) {
                getLogger().error("StandardOPCUAService.getDataInCSV(): Error parsing result for" + nodesToRead[i].getNodeId().toString());
                valueLine = "";
            }
            if (valueLine.isEmpty())
                continue;

            serverResponse += valueLine;
        }
        return serverResponse;
    }

    private String getDataInTempus(ReadValueId nodesToRead[], DataValue values[], String returnTimestamp, String excludeNullValue,
                                   String nullValueString, boolean longTimestamp, boolean isGateway, String deviceName) {

        String returnValue = "";
        String ts;
        String name;
        Object value;
        Object quality;
        com.stoecklin.processors.DataValue dataValue = null;
        ArrayList<com.stoecklin.processors.DataValue> dataValueList = new ArrayList<>();
        String newTimeStamp = "";

        for (int i = 0; i < values.length; i++) {

            try {
                // Add JSON Object for sensor values
                if (excludeNullValue.equals("true") && values[i].getValue().toString().equals(nullValueString)) {
                    getLogger().debug("StandardOPCUAService.getDataInTempus(): Null value returned for " + values[i].getValue().toString() + " -- Skipping because property is set");
                    continue;
                }

                ts = getTimeStamp(values[i], returnTimestamp, longTimestamp); //timestamp

                if (ts == null){
                    continue;
                }

                // handle multiple timestamps
                if (! newTimeStamp.equalsIgnoreCase(ts) )
                {
                    newTimeStamp = ts;
                    dataValue = new com.stoecklin.processors.DataValue();
                    dataValue.setTimeStamp(newTimeStamp);
                    dataValueList.add(dataValue);
                }

                // get name, value and quality
                String[] key = nodesToRead[i].getNodeId().toString().split("=");
                name =  key[key.length - 1].toString();
                value = values[i].getValue().getValue();

                if (value == null){
                    continue;
                }
                quality = values[i].getStatusCode().getValue();
                dataValue.addValue(name,value);
                dataValue.addValue(name+"-quality",quality);
            } catch (Exception ex) {
                getLogger().error("StandardOPCUAService.getDataInTempus(): Error parsing result for" + nodesToRead[i].getNodeId().toString());
            }
        }

        try {

            if (isGateway) {

                GatewayValue gwValue = new GatewayValue();
                gwValue.setDeviceName(deviceName);

                for (int i = 0; i < dataValueList.size(); i++) {
                    gwValue.addDataValue(dataValueList.get(i));
                }

                ObjectMapper gwMapper = new ObjectMapper();
                SimpleModule module = new SimpleModule();
                module.addSerializer(GatewayValue.class, new GatewayValueSerializer());
                gwMapper.registerModule(module);

                returnValue = gwMapper.writeValueAsString(gwValue);
            } else {

                DeviceValue deviceValue = new DeviceValue();

                for (int i = 0; i < dataValueList.size(); i++) {
                    deviceValue.addDataValue(dataValueList.get(i));
                }

                ObjectMapper dMapper = new ObjectMapper();
                SimpleModule module = new SimpleModule();
                module.addSerializer(DeviceValue.class, new DeviceValueSerializer());
                dMapper.registerModule(module);

                returnValue = dMapper.writeValueAsString(deviceValue);
            }

        } catch (JsonProcessingException e) {
            getLogger().error("StandardOPCUAService.getDataInTempus(): Error generating Tempus JSON: " + e.getMessage());
        }

        return returnValue;

    }

    private String getTimeStamp(DataValue value, String returnTimestamp, boolean longTimestamp) throws Exception{
        String ts = null;
        LocalDateTime ldt = null;
        DateTimeFormatter formatPattern = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // Get Timestamp
        try {

            if(!value.isNull())
            {
                if (returnTimestamp.equals("ServerTimestamp")) {
                    if (longTimestamp) {
                        ts = value.getServerTimestamp().getTimeInMillis()+"";
                    } else {
                        ts = Utils.convertStringDateFormat(value.getServerTimestamp().toString(), "MM/dd/yy HH:mm:ss.SSSSSSS z", "yyyy-MM-dd HH:mm:ss.SSS");
                    }
                }
                if (returnTimestamp.equals("SourceTimestamp")) {
                    if (longTimestamp) {
                        ts = value.getSourceTimestamp().getTimeInMillis()+"";
                    } else {
                        ts = Utils.convertStringDateFormat(value.getSourceTimestamp().toString(), "MM/dd/yy HH:mm:ss.SSSSSSS z", "yyyy-MM-dd HH:mm:ss.SSS");
                    }
                }
            }

        } catch (Exception ex) {
            throw ex;
        }
        return ts;
    }

    private boolean validateEndpoint(Client client, String security_policy, String discoveryServer, String url) {

        // TODO This method should provide feedback
        final ComponentLog logger = getLogger();

        // Retrieve end point list
        EndpointDescription[] endpoints = null;

        // This assumes the provided url is co-served with the discovery server
        try {
            endpoints = client.discoverEndpoints(discoveryServer);
        } catch (ServiceResultException e1) {
            logger.error("StandardOPCUAService.validateEndpoint(): " + e1.getMessage());
        }

        // Finally confirm the provided endpoint is in the list of
        endpoints = EndpointUtil.selectByUrl(endpoints, url);

        logger.debug("StandardOPCUAService.validateEndpoint(): " + endpoints.length + " endpoints found");

        // There should only be one item left in the list
        // TODO Servers with multiple nic cards have more than one left in the list
        if (endpoints.length == 0) {
            logger.debug("StandardOPCUAService.validateEndpoint(): No suitable endpoint found from " + url);
            return false;
        }
        return true;
    }
}
