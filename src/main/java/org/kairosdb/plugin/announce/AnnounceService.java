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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AnnounceService implements KairosDBService
{
	public static final Logger logger = LoggerFactory.getLogger(AnnounceService.class);

	private UUID m_announceId = UUID.randomUUID();
	private String m_nodeId = "312d61f9-0784-41e3-8c37-b9142b20b791";
	private final String m_environment;
	private final String m_pool;
	private final ScheduledExecutorService m_executorService;
	private final List<String> m_discoveryUrls;

	private boolean announced = false;
	private boolean logMessages = false;

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
	@Named("kairosdb.plugin.announce.period")
	private int announcePeriod = 5;


	@Inject
	public AnnounceService(@Named("kairosdb.plugin.announce.discovery.hosts") String discoveryHosts,
	                       @Named("kairosdb.plugin.announce.discovery.port") int discoveryPort,
	                       @Named("kairosdb.plugin.announce.environment") String environment,
	                       @Named("kairosdb.plugin.announce.pool") String pool)
	{
		m_environment = environment;
		m_pool = pool;

		m_discoveryUrls = new ArrayList<String>();

		String[] hosts = discoveryHosts.split(",");
		for (String host : hosts)
		{
			host = host.trim();
			m_discoveryUrls.add("http://" + host + ":" + discoveryPort);
		}

		m_executorService = Executors.newSingleThreadScheduledExecutor();
	}

	/**
	 * Announces service
	 *
	 * @return true if announce succeeded false otherwise.
	 */
	private boolean announce(String discoveryURL) throws JSONException, IOException
	{
		boolean ret = false;

		JSONObject announce = new JSONObject();
		HttpClient client = new DefaultHttpClient();

		announce.put("environment", m_environment);
		announce.put("pool", m_pool);
		announce.put("location", "/" + m_nodeId);

		JSONArray services = new JSONArray();
		announce.put("services", services);

		JSONObject service = new JSONObject();
		services.put(service);

		service.put("id", m_announceId.toString());
		service.put("type", "pulse-kairos");

		JSONObject props = new JSONObject();
		props.put("http", "http://" + m_hostIp + ":" + m_httpPort);
		props.put("http-external", "http://" + m_hostName + ":" + m_httpPort);
		props.put("telnet", m_hostIp + ":" + m_telnetPort);

		service.put("properties", props);

		HttpPut post = new HttpPut(discoveryURL + "/v1/announcement/" + m_nodeId);
		post.setHeader("User-Agent", m_nodeId);
		post.setHeader("Content-Type", "application/json");

		post.setEntity(new StringEntity(announce.toString()));

		HttpResponse response = client.execute(post);
		if (response.getStatusLine().getStatusCode() == 202)
			ret = true;

		return (ret);
	}

	@Override
	public void start() throws KairosDBException
	{
		m_executorService.scheduleAtFixedRate(new AnnounceTask(), 0, announcePeriod, TimeUnit.SECONDS);
	}

	@Override
	public void stop()
	{
		try
		{
			m_executorService.shutdown();
			HttpClient client = new DefaultHttpClient();

			for (String url : m_discoveryUrls)
			{
				HttpDelete delete = new HttpDelete(url + "/v1/announcement/" + m_nodeId);
				delete.setHeader("User-Agent", m_nodeId);
				HttpResponse response = client.execute(delete);
				logger.info("Announcement repeal status: " + response.getStatusLine().getStatusCode());
			}
		}
		catch (IOException e)
		{
			logger.warn("Failed to repeal announcement", e);
		}
	}

	private class AnnounceTask implements Runnable
	{
		@Override
		public void run()
		{
			List<Message> messages = new ArrayList<Message>();
			boolean success = false;
			for (String url : m_discoveryUrls)
			{
				try
				{
					success = announce(url);
					if (success)
					{
						messages.add(new Message(Level.INFO, "Announce to Discovery Server (" + url + ") Succeeded."));
						break;
					}
					else
					{
						messages.add(new Message(Level.WARN, "Announce to Discovery Server (" + url + ") Failed."));
					}
				}
				catch (JSONException e)
				{
					messages.add(new Message(Level.WARN, "Failed to announce", e));
				}
				catch (IOException e)
				{
					messages.add(new Message(Level.WARN, "Failed to announce", e));
				}
			}
			setAnnounceState(success);

			if (logMessages)
			{
				for (Message message : messages)
				{
				 	message.logMessage();
				}
			}
		}
	}

	private void setAnnounceState(boolean state)
	{
		logMessages = announced != state;
		announced = state;
	}

	private enum Level
	{
		INFO, WARN
	}

	private class Message
	{
		private Level level;
		private String message;
		private Exception exception;


		public Message(Level level, String message)
		{
			this.level = level;
			this.message = message;
		}

		public Message(Level level, String message, Exception exception)
		{
			this.level = level;
			this.message = message;
			this.exception = exception;
		}

		public void logMessage()
		{
			if (level == Level.WARN)
			{
				if (exception != null)
					logger.warn(message, exception);
				else
					logger.warn(message);
			}
			else
			{
				if (exception != null)
					logger.info(message, exception);
				else
					logger.info(message);
			}
		}
	}
}
