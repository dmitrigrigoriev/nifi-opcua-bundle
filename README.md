[![License](http://img.shields.io/:license-Apache%202-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)

# nifi-opcua-bundle
These processors and associated controller service allow NiFi access to OPC UA servers in a read-only fashion. This bundle
provides 2 processors, GetOPCNodeList and GetOPCData. GetOPCNodeList allows access to the tags that are currently in the OPCUA server,
GetOPCData takes a list of tags and queries the OPC UA server for the values. The StandardOPCUAService provides the connectivity
to the OPCUA server so that multiple processors can leverage the same connection/session information.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Getting Started](#getting-started)
- [Usage](#usage)
- [License](#license)

## Features

This processor aims to provide a few key features:

* Access to list the tag information currently in the OPC UA server
* Access to query the data currently in the OPC UA server
* Optional null value exclusion
* Configurable timestamp selection

## Requirements

* JDK 1.8 at a minimum
* Maven 3.1 or newer
* Git client (to build locally)
* OPC Foundation Stack (instructions to build below)

## Getting Started

### Build the OPC Foundation Stack

Clone the OPC Foundation GitHub repository

    git clone https://github.com/OPCFoundation/UA-Java-Legacy.git

Change directory into the UA-Java-Legacy directory

    cd UA-Java-Legacy

Checkout the 1.4.1 release of the build by executing

    git checkout 9006208
    
Execute the package phase (NOTE: at the time of this writing, there were test failures due to invalid tests, there are currently PR's
out there to address these, but they have not been merged into master, therefore we need to skip tests)

    mvn package -DskipTests
    
### Setup the local build environment for the processor    

To build the library and get started first off clone the GitHub repository 

    git clone https://github.com/hashmapinc/nifi-opcua-bundle.git
    
Copy the jar from the previous step where we built the OPC Foundation code from the cloned repo of the OPC foundation 
code (Where repo_location is the location of where the cloned repo is and {version} is the version of the OPC Foundation 
code that was cloned.)

    {repo_location}/UA-Java/target/opc-ua-stack-{version}-SNAPSHOT.jar

Place that file into the following directory (where repo_location is the location of where the nifi-opcua-bundle repo was cloned.)

    {repo_location}/nifi-opcua-bundle/opc-deploy-local/src/main/resources
    
Change directory into the root of the nifi-opcua-bundle codebase located in

    {repo_location}/nifi-opcua-bundle
    
Execute a maven clean install

    mvn clean install
    
A Build success message should appear
   
    [INFO] ------------------------------------------------------------------------
    [INFO] BUILD SUCCESS
    [INFO] ------------------------------------------------------------------------
    [INFO] Total time: 28.375 s
    [INFO] Finished at: 2021-07-20T11:48:25+01:00
    [INFO] ------------------------------------------------------------------------

A NAR file should be located in the following directory

    {repo_location}/nifi-opcua-bundle/nifi-opcua-bundle/nifi-opcua-bundle-nar/target
    
Copy this NAR file to the /lib directory and restart (or start) Nifi.

## Usage

Set the following properties:

| Property                | Value        | Notes                                                                                                      |
|-------------------------|--------------|------------------------------------------------------------------------------------------------------------|
| Recursive Depth         | 3            | This is how many child levels to traverse from the top                                                     |
| Starting Nodes          | No value set | This will be blank as this is what we are determining in this step                                         |
| Print Indentation       | Yes          | This just helps visualize the hierarchy in the flow file once the data is received                         |
| Remove Expanded Node ID | No           | This is for when we are generating a tag list to query, not needed in this step                            |
| Max References Per Node | 1000         | It is not necessary to set this at this stage as we are just looking for the parent node of the data tags. |

You should be presented with a list of controller services, if you are on a fresh instance of NiFi you should only see the StandardOPCUAService controller
service that we created above. Click on the pencil to the right of the controller service that we created above. This will take you to the Configure Controller
Service modal box. Click on the **PROPERTIES** tab. and configure it, replacing the Endpoint URL with your own opc.tcp//{ipaddress}:{port}
endpoint.

Click apply and enable it by clicking on the little lightining bolt next to the service when you are back at the controller service list.

### Getting the Data

### Reconfigure the GetOPCNodeList processor

Now that we know what our starting node should be we are ready to reconfigure our processor. Right-click on the GetOPCNodeList processor and
select **Configure** from the context menu. Click on the **PROPERTIES** tab.

An explanation of these different properties is in the table below:

| Property                | Value                                   | Notes                                                                                                                                                                                   |
|-------------------------|-----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| OPC UA Service          | StandardOPCUAService                    | Keep this the same as before                                                                                                                                                            |
| Recursive Depth         | 0                                       | Now that we know the node that contains the tag data, we don't need to traverse from the starting node anymore, so we set this to 0.                                                    |
| Print Indentation       | No                                      | Now that we know what we are looking for, we don't have to make it easy to read anymore.                                                                                                |
| Start Node              | ns=1;s=Server.Topic.Data.Value          | This is the value we found in the previous step to be the root of the tree that contained the tag data. (Also flowfile attributes can be used here). You can specify list of the nodes  |
|                         |                                         | separated by new line (Shift+Enter)                                                                                                                                                     |
| Node Filter             |                                         | Nodes Filter (Java RegExp), * - return all nodes. Filter should be specified for every start node in the list. List here also separated by new line (Shift+Enter). Support flowfile     |
|                         |                                         | attributed                                                                                                                                                                              |
| Remove Expanded Node Id | Yes                                     | This will remove the opcfoundation header that is not a valid tag for querying.                                                                                                         |
| Max References Per Node | 1000                                    | If you have more than 1000 tags in your server you will want to increase this. NOTE, if you have a lot of tags, you might want to split the query into chunks via different processors. |

Click Apply. Now the processor is configured to simply return a tag list as shown below.
This processor writes following attributes in the flowfile: recursiveDepth, nodeFilter, node, namespace

### Configuring the GetOPCData processor

Head back to the NiFi canvas now, and right-click on the GetOPCData processor and select **Configure** from the context menu
to configure the processor. Go ahead and auto-terminate the failure relationship, as was done above, by checking the checkbox next to 
**Failure**. Click on the **PROPERTIES** tab and fill out the information as below.

NOTE: You will want to use the same controller service instance as created above for the GetOPCNodeList processor.

The description of the properties is in the table below:

| Property           | Value                | Notes                                                                                                                                                                                     |
|--------------------|----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| OPC UA Service     | StandardOPCUAService | The same instance of the controller service that was created for the GetOPCNodeList processor                                                                                             |
| Return Timestamp   | Both                 | This will return both the source and the server timestamp that was requested, the other options will just return one or the other.                                                        |
| Exclude Null Value | false                | If your server has a well known null value and you would like to prevent pulling this data, set this to true and enter that well known value into the optional Null Value String property |
| Null Value String  | <blank>              | If Exclude Null Value is set to true, then this will be the value that is used to filter out the tags.                                                                                    |

### Next Steps

This is fine for a test, however, you would want to modify this in production use. Ideally you would have 2 flows, one that updates the tag list, and 
one that gets the data for the tags. The one that updates the tag list would run at a lower frequency. Additionally, depending on the number of tags,
the queries should be split up so that they don't overwhelm the server. 

## License

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


## Next development points

Writing in OPC Server (new processor like WriteOPCData)
Subsribe to events (new processor SubsribeToOPCEvents)
Reconnect issues (at least reconnect doesn`t work for Tany OPC-UA Server) - for now the service should be manually stopped and started again.

 

