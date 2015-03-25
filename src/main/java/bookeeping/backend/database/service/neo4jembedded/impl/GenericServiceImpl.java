package bookeeping.backend.database.service.neo4jembedded.impl;

import java.util.HashMap;
import java.util.Map;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import bookeeping.backend.database.MandatoryProperties;
import bookeeping.backend.database.connection.singleton.Neo4JEmbeddedConnection;
import bookeeping.backend.database.service.GenericService;
import bookeeping.backend.exception.NodeNotFound;
import bookeeping.backend.exception.NodeUnavailable;
import bookeeping.backend.exception.VersionNotFound;

public class GenericServiceImpl implements GenericService
{
	private Neo4JEmbeddedConnection neo4jEmbeddedConnection;
	private GraphDatabaseService graphDatabaseService;
	private CommonCode commonCode;
	
	public GenericServiceImpl()
	{
		this.neo4jEmbeddedConnection = Neo4JEmbeddedConnection.getInstance();
		this.graphDatabaseService = this.neo4jEmbeddedConnection.getGraphDatabaseServiceObject();
		this.commonCode = new CommonCode();
	}
	
	@Override
	public String createNewVersion(String commitId, String nodeId, Map<String, Object> changeMetadata, Map<String, Object> changedProperties) throws NodeNotFound
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			Node versionedNode = this.commonCode.createNodeVersion(commitId, nodeId, changeMetadata, changedProperties);
			String versionedNodeId = (String) versionedNode.getProperty(MandatoryProperties.nodeId.name());
			transaction.success();
			return versionedNodeId;
		}
	}
	
	@Override
	public Map<String, Object> getNode(String nodeId) throws NodeNotFound
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			Map<String, Object> nodeProperties = new HashMap<String, Object>();
			Node node = this.commonCode.getNode(nodeId);
			for(String key : node.getPropertyKeys())
			{
				nodeProperties.put(key, node.getProperty(key));
			}
			
			transaction.success();
			return nodeProperties;
		}
	}
	
	@Override
	public Map<String, Object> getNodeVersion(String nodeId, int version) throws NodeNotFound, VersionNotFound
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			Node node = this.commonCode.getNodeVersion(nodeId, version);
			Map<String, Object> properties = new HashMap<String, Object>();
			
			Iterable<String> keys = node.getPropertyKeys();
			for(String key : keys)
			{
				properties.put(key, node.getProperty(key));
			}
			
			transaction.success();
			return properties;
		}
	}

	@Override
	public void deleteNodeTemporarily(String commitId, String nodeId) throws NodeNotFound, NodeUnavailable
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			this.commonCode.deleteNodeTemporarily(commitId, nodeId);
			transaction.success();
		}
	}
}