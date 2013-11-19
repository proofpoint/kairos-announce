/*
 * Copyright 2013 Proofpoint Inc.
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

package org.kairosdb.plugin.announce;

import com.google.inject.name.Named;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kairosdb.core.KairosDBService;
import org.kairosdb.core.exception.KairosDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 Created with IntelliJ IDEA.
 User: bhawkins
 Date: 11/6/13
 Time: 10:04 AM
 To change this template use File | Settings | File Templates.
 */
public class AnnounceService implements KairosDBService
{
	public static final Logger logger = LoggerFactory.getLogger(AnnounceService.class);

	private UUID m_announceId = UUID.randomUUID();
	private String m_nodeId = "312d61f9-0784-41e3-8c37-b9142b20b791";
	private final String m_discoveryUrl;
	private final String m_environment;
	private final String m_pool;
	private final ScheduledExecutorService m_executorService;

	@Inject
	@Named("HOSTNAME")
	private String m_hostName = "localhost";

	@Inject
	@Named("HOST_IP")
	private String m_hostIp = "127.0.0.1";

	@Inject
	@Named("kairosdb.telnetserver.port")
	private int m_telnetPort = 4242;

	@Inject
	@Named("kairosdb.jetty.port")
	private int m_httpPort = 8080;

	@Inject
	@Named("kairosdb.plugin.announce.retry")
	private int m_retry = 5;

	@Inject
	public AnnounceService(@Named("kairosdb.plugin.announce.discovery.url")String discoveryUrl,
			@Named("kairosdb.plugin.announce.environment")String environment,
			@Named("kairosdb.plugin.announce.pool")String pool)
	{
		m_discoveryUrl = discoveryUrl;
		m_environment = environment;
		m_pool = pool;

		m_executorService = Executors.newSingleThreadScheduledExecutor();
	}

	/**
	 Announces service
	 @return true if announce succeeded false otherwise.
	 */
	private boolean announce()
	{
		boolean ret = false;

		JSONObject announce = new JSONObject();
		try
		{
			HttpClient client = new DefaultHttpClient();

			//announce.put("nodeId", m_nodeId);
			announce.put("environment", m_environment);
			announce.put("pool", m_pool);
			announce.put("location", "/"+m_nodeId);

			JSONArray services = new JSONArray();
			announce.put("services", services);

			JSONObject service = new JSONObject();
			services.put(service);

			service.put("id", m_announceId.toString());
			service.put("type", "pulse-kairos");

			JSONObject props = new JSONObject();
			props.put("http", "http://"+m_hostIp+":"+m_httpPort);
			props.put("http-external", "http://"+ m_hostName +":"+m_httpPort);
			props.put("telnet", m_hostIp+":"+m_telnetPort);

			service.put("properties", props);

			System.out.println(announce.toString());
			HttpPut post = new HttpPut(m_discoveryUrl+"/v1/announcement/"+m_nodeId);
			post.setHeader("User-Agent", m_nodeId);
			post.setHeader("Content-Type", "application/json");

			post.setEntity(new StringEntity(announce.toString()));

			HttpResponse response = client.execute(post);
			if (response.getStatusLine().getStatusCode() == 202)
			{
				ret = true;
				logger.info("Announce to Discovery Server ("+m_discoveryUrl+") Succeeded.");
			}
			else
				logger.warn("Announce to Discovery Server (" + m_discoveryUrl + ") Failed.");
		}
		catch (JSONException e)
		{
			logger.warn("Failed to announce", e);
		}
		catch (ClientProtocolException e)
		{
			logger.warn("Failed to announce", e);
		}
		catch (IOException e)
		{
			logger.warn("Failed to announce", e);
		}

		return (ret);
	}

	@Override
	public void start() throws KairosDBException
	{
		if (!announce())
		{
			//Start announce thread to keep trying
			m_executorService.schedule(new AnnounceTask(), m_retry, TimeUnit.MINUTES);
		}
	}

	@Override
	public void stop()
	{
		try
		{
			m_executorService.shutdown();
			HttpClient client = new DefaultHttpClient();
			HttpDelete delete = new HttpDelete(m_discoveryUrl+"/v1/announcement/"+m_nodeId);
			delete.setHeader("User-Agent", m_nodeId);
			HttpResponse response = client.execute(delete);
			logger.info("De-Announce status: "+response.getStatusLine().getStatusCode());
		}
		catch (IOException e)
		{
			logger.warn("Failed to de-announce", e);
		}
	}

	private class AnnounceTask implements Runnable
	{
		@Override
		public void run()
		{
			if (!announce())
				m_executorService.schedule(new AnnounceTask(), m_retry, TimeUnit.MINUTES);
		}
	}
}
