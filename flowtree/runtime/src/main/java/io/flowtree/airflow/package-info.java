/*
 * Copyright 2018 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Integration layer between Apache Airflow and the FlowTree workflow engine.
 *
 * <p>Classes in this package expose a lightweight Jetty HTTP endpoint (default
 * port 7070) that Airflow (or any HTTP client) can use to submit shell commands
 * as FlowTree {@link io.flowtree.job.Job} instances. The
 * {@link io.flowtree.airflow.AirflowJobFactory} owns the singleton endpoint and
 * enqueues {@link io.flowtree.airflow.AirflowJob} objects that are subsequently
 * dispatched to FlowTree worker nodes for execution.
 *
 * @author  Michael Murray
 */
package io.flowtree.airflow;