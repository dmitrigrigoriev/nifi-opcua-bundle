package com.stoecklin.processors;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.opcfoundation.ua.builtintypes.ExpandedNodeId;
import org.opcfoundation.ua.builtintypes.NodeId;
import org.opcfoundation.ua.builtintypes.UnsignedInteger;
import org.opcfoundation.ua.core.Identifiers;

@Tags({"OPC", "OPCUA", "UA"})
@CapabilityDescription("Retrieves the namespace from an OPC UA server")
@SeeAlso({})
@EventDriven
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})

public class GetOPCNodeList extends AbstractProcessor {

	private String print_indentation = "No";
	private String remove_opc_string = "No";
	private Integer max_recursiveDepth;
	private Integer max_reference_per_node;

	public static final PropertyDescriptor OPCUA_SERVICE = new PropertyDescriptor.Builder()
			.name("OPC UA Service")
			.description("Specifies the OPC UA Service that can be used to access data")
			.required(true)
			.identifiesControllerService(OPCUAService.class)
			.build();

	public static final PropertyDescriptor START_NODE = new PropertyDescriptor.Builder()
			.name("Start Node")
			.description("Start Node for Browsing")
			.required(false)
			.addValidator(StandardValidators.NON_BLANK_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
			.build();

	public static final PropertyDescriptor NODE_FILTER = new PropertyDescriptor.Builder()
			.name("Node Filter")
			.description("Provide regular expression for nodes you want to fetch node-list. Default it will fetch node-list for all nodes starting from root node. Separate multiple regex with a pipe(|)")
			.required(false)
			.addValidator(Validator.VALID)
			.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
			.build();

	public static final PropertyDescriptor RECURSIVE_DEPTH = new PropertyDescriptor
			.Builder().name("Recursive Depth")
			.description("Maximum depth from the starting node to read, Default is 3")
			.required(true)
			.defaultValue("3")
			.addValidator(StandardValidators.INTEGER_VALIDATOR)
			.build();

	public static final PropertyDescriptor PRINT_INDENTATION = new PropertyDescriptor
			.Builder().name("Print Indentation")
			.description("Should Nifi add indentation to the output text")
			.required(true)
			.allowableValues("No", "Yes")
			.defaultValue("No")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.build();

	public static final PropertyDescriptor REMOVE_OPC_STRING = new PropertyDescriptor
			.Builder().name("Remove Expanded Node ID")
			.description("Should remove Expanded Node ID string from the list")
			.required(true)
			.allowableValues("No", "Yes")
			.defaultValue("No")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.build();

	public static final PropertyDescriptor MAX_REFERENCE_PER_NODE = new PropertyDescriptor
			.Builder().name("Max References Per Node")
			.description("The number of Reference Descriptions to pull per node query.")
			.required(true)
			.defaultValue("1000")
			.addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
			.build();

	public static final Relationship SUCCESS = new Relationship.Builder()
			.name("Success")
			.description("Successful OPC read")
			.build();

	public static final Relationship FAILURE = new Relationship.Builder()
			.name("Failure")
			.description("Failed OPC read")
			.build();

	private List<PropertyDescriptor> descriptors;

	private Set<Relationship> relationships;

	@Override
	protected void init(final ProcessorInitializationContext context) {
		final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
		descriptors.add(OPCUA_SERVICE);
		descriptors.add(RECURSIVE_DEPTH);
		descriptors.add(START_NODE);
		descriptors.add(NODE_FILTER);
		descriptors.add(PRINT_INDENTATION);
		descriptors.add(REMOVE_OPC_STRING);
		descriptors.add(MAX_REFERENCE_PER_NODE);

		this.descriptors = Collections.unmodifiableList(descriptors);

		final Set<Relationship> relationships = new HashSet<Relationship>();
		relationships.add(SUCCESS);
		relationships.add(FAILURE);
		this.relationships = Collections.unmodifiableSet(relationships);
	}

	@Override
	public Set<Relationship> getRelationships() {
		return this.relationships;
	}

	@Override
	public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return descriptors;
	}

	@OnScheduled
	public void onScheduled(final ProcessContext context) {
		print_indentation = context.getProperty(PRINT_INDENTATION).getValue();
		max_recursiveDepth = Integer.valueOf(context.getProperty(RECURSIVE_DEPTH).getValue());
		remove_opc_string = context.getProperty(REMOVE_OPC_STRING).getValue();
		max_reference_per_node = Integer.valueOf(context.getProperty(MAX_REFERENCE_PER_NODE).getValue());
	}

	@Override
	public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {

		FlowFile flowFile = session.get();

		List<FlowFile> flowFiles = new ArrayList<FlowFile>();
		boolean success = true;
		
		final ComponentLog logger = getLogger();

		try {
			// Submit to getValue
			final OPCUAService opcUAService = context.getProperty(OPCUA_SERVICE)
					.asControllerService(OPCUAService.class);

			if (opcUAService.updateSession()) {
				logger.info("GetOPCNodeList.onTrigger(): Session update complete");
			} else {
				logger.error("GetOPCNodeList.onTrigger(): Session update failed");
				success = false;
			}

			String nodes = "";
			String filters = "";
			
			if (flowFile != null) {
				nodes = context.getProperty(START_NODE).evaluateAttributeExpressions(flowFile).getValue();
				filters = context.getProperty(NODE_FILTER).evaluateAttributeExpressions(flowFile).getValue();
			} else {
				nodes = context.getProperty(START_NODE).getValue();
				filters = context.getProperty(NODE_FILTER).getValue();				
			}

			String[] nodesList = nodes.split("\\n");
			String[] filterList = filters.split("\\n");
			if ( nodesList.length != filterList.length ) {
				logger.error("GetOPCNodeList.onTrigger(): Amount filters and nodes should be the same. Check your settings.");
				success = false;
			}
			
			if ( success ) {
				
				for (int i = 0; i < nodesList.length; i++ ) {
					String start_node = nodesList[i];
					String node_filter = filterList[i];
					
					logger.info("GetOPCNodeList.onTrigger(): " + start_node + " [filter]: " + node_filter);
					ExpandedNodeId start_node_id;

					if (start_node.isEmpty()) {
					  start_node_id = new ExpandedNodeId((Identifiers.RootFolder));
					}
					else {
					  start_node_id = ExpandedNodeId.parseExpandedNodeId(start_node);
					}

					Pattern pattern;
					if (node_filter == null || "*".equals(node_filter) ) {
						pattern = Pattern.compile("[^\\.].*");
					} else {
						pattern = Pattern.compile(node_filter);
					}

					String nameSpace = opcUAService.getNameSpace(print_indentation, max_recursiveDepth, start_node_id, pattern, new UnsignedInteger(max_reference_per_node));
					if (StringUtils.isNotBlank(nameSpace)) {
						StringBuilder stringBuilder = new StringBuilder();
						stringBuilder.append(nameSpace);

						// Write the results back out to a flow file
						FlowFile newFlowFile = session.create();

						try {
							newFlowFile = session.putAttribute(newFlowFile, "recursiveDepth", Integer.toString(max_recursiveDepth));
							newFlowFile = session.putAttribute(newFlowFile, "nodeFilter", node_filter);
							newFlowFile = session.putAttribute(newFlowFile, "node", start_node);
							newFlowFile = session.putAttribute(newFlowFile, "namespace", nameSpace);							
							newFlowFile = session.write(newFlowFile, new OutputStreamCallback() {
								public void process(OutputStream out) throws IOException {

									switch (remove_opc_string) {

										case "Yes": {
											String str = stringBuilder.toString();
											String parts[] = str.split("\\r?\\n");
											String outString = "";
											for (int i = 0; i < parts.length; i++) {
												if (parts[i].startsWith("nsu")) {
													continue;
												}
												outString = outString + parts[i] + System.getProperty("line.separator");
											}
											outString.trim();
											out.write(outString.getBytes());
											break;
										}
										case "No": {
											out.write(stringBuilder.toString().getBytes());
											break;
										}

									}
								}
							});
							session.transfer(newFlowFile, SUCCESS);
						} catch (ProcessException ex) {
							session.remove(newFlowFile);
							logger.error("GetOPCNodeList.onTrigger(): Unable to process: " + ex.getMessage());
							success = false;
							break;
						}
					} else {
						logger.error("GetOPCNodeList.onTrigger(): No namespace found: opcUAService.getNameSpace(...) returned empty or null. Start node: " + start_node);
						success = false;
						break;
					}
				}
			}
		} catch(Exception e) {
			logger.error("GetOPCNodeList.onTrigger(): Error: "+e.getMessage());
			success = false;
		}

		if ( success && flowFiles.size() > 0 ) {
			session.remove(flowFile);
		} else {
			session.transfer(flowFile, FAILURE);
		}
	}
}