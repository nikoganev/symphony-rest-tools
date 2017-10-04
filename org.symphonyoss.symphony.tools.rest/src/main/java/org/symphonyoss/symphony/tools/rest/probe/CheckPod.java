/*
 *
 *
 * Copyright 2017 Symphony Communication Services, LLC.
 *
 * Licensed to The Symphony Software Foundation (SSF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.symphonyoss.symphony.tools.rest.probe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateParsingException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.symphonyoss.symphony.jcurl.JCurl;
import org.symphonyoss.symphony.jcurl.JCurl.Response;
import org.symphonyoss.symphony.tools.rest.SrtCommand;
import org.symphonyoss.symphony.tools.rest.model.IPod;
import org.symphonyoss.symphony.tools.rest.model.IModelObject;
import org.symphonyoss.symphony.tools.rest.model.ModelObject;
import org.symphonyoss.symphony.tools.rest.model.osmosis.ComponentStatus;
import org.symphonyoss.symphony.tools.rest.model.osmosis.IComponent;
import org.symphonyoss.symphony.tools.rest.util.Console;
import org.symphonyoss.symphony.tools.rest.util.IObjective;
import org.symphonyoss.symphony.tools.rest.util.home.ISrtHome;

import com.fasterxml.jackson.databind.JsonNode;

public class CheckPod extends SrtCommand
{
  private static final String      PROGRAM_NAME                = "CheckPod";

  private IPod                     pod_;
  private boolean                  structureChange_;
  private Set<IModelObject> changedComponents_          = new HashSet<>();

  private IObjective podObjective_;
//  private Set<IModelObject> structureChangedComponents_ = new HashSet<>();
  
  public static void main(String[] argv) throws IOException
  {
    new CheckPod(argv).run();
  }

  public CheckPod(Console console, ISrtHome srtHome)
  {
    super(PROGRAM_NAME, console, srtHome);
  }

  public CheckPod(String[] argv)
  {
    super(PROGRAM_NAME, argv);
  }
  
  @Override
  protected void init()
  {
    super.init();
    interactive_.setCount(0);

    withHostName(true);
    withKeystore(false);
    withTruststore(false);
    
    podObjective_ = getConsole().createObjective("Check Pod");
  }

  @Override
  public void execute()
  {
    pod_ = getSrtHome().getPodManager().getPod(getFqdn());

    if(pod_ == null)
    {
      getConsole().flush();
      getConsole().error(getFqdn() + " is not a known pod. Try probe instead?");
      return;
    }
    
    println("Pod Configuration");
    println("=================");
    
    pod_.print(getConsole().getOut());

    println();
    
    int totalWork = 1;
    getConsole().beginTask("Checking " + getFqdn(), totalWork);
    
    probePod();
    
    getConsole().worked(1);
    
    getConsole().printObjectives();
  }

  

  private void probePod()
  {
    try
    {
      
      println("Checking Pod");
      println("=============");
      
      pod_.visit((component) -> 
      {
        component.resetStatus();
      });
      
      URL url = createURL(pod_.getPodUrl(),
          POD_HEALTHCHECK_PATH);
      
      JCurl jCurl = getJCurl().build();
      HttpURLConnection connection = jCurl.connect(url);
      
      int responseCode = connection.getResponseCode();
      
      switch(responseCode)
      {
        case 200:
        case 500:
          break;
          
        default:
          println("Healthcheck failed.");
          pod_.setComponentStatus(ComponentStatus.Failed, "Error " + connection.getResponseCode());
          pod_.getManager().modelObjectChanged(pod_);
          return;
      }
  
      Response response = jCurl.processResponse(connection);
      JsonNode healthJson = response.getJsonNode();
  
      if (healthJson == null)
      {
        println("This looks a lot like a Symphony Pod, but it isn't");
        pod_.setComponentStatus(ComponentStatus.Failed, "Non-JSON response from HealthCheck");
        pod_.getManager().modelObjectChanged(pod_);
        return;
      }
  
      if (!healthJson.isObject())
      {
        println("This looks like a Symphony Pod, but the healthcheck returns something other than an object");
        println(healthJson);
        pod_.setComponentStatus(ComponentStatus.Failed, String.format("Received a JSON %s from HealthCheck, but we expect an object", healthJson.getNodeType()));
        pod_.getManager().modelObjectChanged(pod_);
        return;
      }
      
      Iterator<String> it = healthJson.fieldNames();
      int failedComponents = 0;
      int totalComponents = 0;
      
      while(it.hasNext())
      {
        String name = it.next();
        boolean healthy = healthJson.get(name).asBoolean();
        
        printf("%30s %s\n", name, healthy);
        
        pod_.getComponent(name, 
            (parent, componentName) -> 
            {
              ModelObject component = new ModelObject(pod_, IComponent.GENERIC_COMPONENT, componentName);
              
              structureChange_ = true;
              return component;
            },
            (existingComponent) -> changedComponents_.add(existingComponent)
        ).setComponentStatus(healthy ? ComponentStatus.OK : ComponentStatus.Failed, "");
        
        if(!healthy)
          failedComponents++;
        
        totalComponents++;
      }
      
      switch(responseCode)
      {
        case 200:
          println("Pod is healthy.");
          
          if(failedComponents == 0)
          {
            pod_.setComponentStatus(ComponentStatus.OK, "Healthcheck OK");
            podObjective_.setStatus(ComponentStatus.OK);
          }
          else
          {
            pod_.setComponentStatus(ComponentStatus.Error, 
                String.format("Healthcheck OK but %d of %d non-critical components failed", failedComponents, totalComponents));
            podObjective_.setStatus(ComponentStatus.Error);
          }
          break;
          
        case 500:
          println("Pod is unwell.");
          pod_.setComponentStatus(ComponentStatus.Failed, 
              String.format("Healthcheck FAILED (%d of %d components failed)", failedComponents, totalComponents));
          podObjective_.setStatus(ComponentStatus.Failed);
          break;
          
          default:
            pod_.setComponentStatus(ComponentStatus.Failed, "Unexpected Error " + responseCode);
            podObjective_.setStatus(ComponentStatus.Failed);
            return;
      }
      
      
      if(structureChange_)
      {
        pod_.getManager().modelObjectStructureChanged(pod_);
      }
      else
      {
//        for(IModelObject component : structureChangedComponents_)
//        {
//          changedComponents_.remove(component);
//          pod_.getManager().modelObjectStructureChanged(component);
//        }
        
        for(IModelObject component : changedComponents_)
        {
          pod_.getManager().modelObjectChanged(component);
        }
      }
    }
    catch(ConnectException e)
    {
      getConsole().error("Cannot connect to pod");
      pod_.setComponentStatus(ComponentStatus.Stopped, "Cannot Connect");
      podObjective_.setStatus(ComponentStatus.Stopped);
      pod_.getManager().modelObjectChanged(pod_);
    }
    catch(IOException | CertificateParsingException e)
    {
      e.printStackTrace(getConsole().getErr());
      podObjective_.setStatus(ComponentStatus.Stopped);
      pod_.getManager().modelObjectChanged(pod_);
    }
  }
}
