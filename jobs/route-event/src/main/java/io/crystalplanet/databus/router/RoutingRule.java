package io.crystalplanet.databus.router;

import java.util.*;

public class RoutingRule {

	public String ruleId;

	public List<String> events;

	public String destinationUrl;

	public List<String> transforms;

	public List<String> filters;
}
