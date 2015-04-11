/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.interpreter.remote;

import org.apache.thrift.TException;
import org.apache.zeppelin.display.AngularObject;
import org.apache.zeppelin.display.AngularObjectRegistry;
import org.apache.zeppelin.interpreter.InterpreterContextRunner;
import org.apache.zeppelin.interpreter.InterpreterGroup;
import org.apache.zeppelin.interpreter.thrift.RemoteInterpreterEvent;
import org.apache.zeppelin.interpreter.thrift.RemoteInterpreterEventType;
import org.apache.zeppelin.interpreter.thrift.RemoteInterpreterService.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 *
 */
public class RemoteInterpreterEventPoller extends Thread {
  Logger logger = LoggerFactory.getLogger(RemoteInterpreterEventPoller.class);
  private RemoteInterpreterProcess interpreterProcess;
  boolean shutdown;
  private InterpreterGroup interpreterGroup;

  public RemoteInterpreterEventPoller(
      InterpreterGroup interpreterGroup,
      RemoteInterpreterProcess interpreterProcess) {
    this.interpreterGroup = interpreterGroup;
    this.interpreterProcess = interpreterProcess;
    shutdown = false;
  }

  @Override
  public void run() {
    Client client = null;

    while (shutdown == false) {
      try {
        client = interpreterProcess.getClient();
      } catch (Exception e1) {
        logger.error("Can't get RemoteInterpreterEvent", e1);
        try {
          synchronized (this) {
            wait(1000);
          }
        } catch (InterruptedException e) {
        }
        continue;
      }

      RemoteInterpreterEvent event = null;
      try {
        event = client.getEvent();
      } catch (TException e) {
        logger.error("Can't get RemoteInterpreterEvent", e);
        try {
          synchronized (this) {
            wait(1000);
          }
        } catch (InterruptedException e1) {
        }
        continue;
      }

      interpreterProcess.releaseClient(client);

      Gson gson = new Gson();

      AngularObjectRegistry angularObjectRegistry = interpreterGroup.getAngularObjectRegistry();

      try {
        if (event.getType() == RemoteInterpreterEventType.NO_OP) {
          continue;
        } else if (event.getType() == RemoteInterpreterEventType.ANGULAR_OBJECT_ADD) {
          AngularObject angularObject = gson.fromJson(event.getData(), AngularObject.class);
          angularObjectRegistry.add(angularObject.getName(), angularObject.get());
        } else if (event.getType() == RemoteInterpreterEventType.ANGULAR_OBJECT_UPDATE) {
          AngularObject angularObject = gson.fromJson(event.getData(), AngularObject.class);
          AngularObject localAngularObject = angularObjectRegistry.get(angularObject.getName());
          if (localAngularObject instanceof RemoteAngularObject) {
            // to avoid ping-pong loop
            ((RemoteAngularObject) localAngularObject).set(
                angularObject.get(), true, false);
          } else {
            localAngularObject.set(angularObject.get());
          }
        } else if (event.getType() == RemoteInterpreterEventType.ANGULAR_OBJECT_REMOVE) {
          AngularObject angularObject = gson.fromJson(event.getData(), AngularObject.class);
          angularObjectRegistry.remove(angularObject.getName());
        } else if (event.getType() == RemoteInterpreterEventType.RUN_INTERPRETER_CONTEXT_RUNNER) {
          InterpreterContextRunner runnerFromRemote = gson.fromJson(
              event.getData(), RemoteInterpreterContextRunner.class);

          interpreterProcess.getInterpreterContextRunnerPool().run(
              runnerFromRemote.getNoteId(), runnerFromRemote.getParagraphId());
        }
      } catch (Exception e) {
        logger.error("Can't handle event " + event, e);
      }
    }
  }

  public void shutdown() {
    shutdown = true;
    synchronized (this) {
      notify();
    }
  }
}
