package database.service.neo4jrest.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;

import database.connection.singleton.Neo4JRestConnection;
import database.neo4j.NodeLabels;
import database.neo4j.RelationshipLabels;
import database.service.FilesystemService;
import exception.DirectoryNotFound;
import exception.DuplicateFilesystem;
import exception.FileNotFound;
import exception.FilesystemNotFound;
import exception.UserNotFound;
import exception.VersionNotFound;

public class FilesystemServiceImpl implements FilesystemService
{
	private Neo4JRestConnection neo4jRestConnection;
	private GraphDatabaseService graphDatabaseService;
	
	public FilesystemServiceImpl()
	{
		this.neo4jRestConnection = Neo4JRestConnection.getInstance();
		this.graphDatabaseService = this.neo4jRestConnection.getGraphDatabaseServiceObject();
	}
	
	@Override
	public void createNewFilesystem(String filesystemId, String userId, Map<String, Object> filesystemProperties) throws UserNotFound, DuplicateFilesystem
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			Node user = null;
			try
			{
				CommonCode commonCode = new CommonCode();
				user = commonCode.getUser(userId);
				commonCode.getFilesystem(userId, filesystemId, false);
				throw new DuplicateFilesystem("ERROR: Filesystem already present! - \"" + filesystemId + "\"");
			}
			catch(FilesystemNotFound filesystemNotFound)
			{
				AutoIncrementServiceImpl autoIncrementServiceImpl = new AutoIncrementServiceImpl();
				
				Node node = this.graphDatabaseService.createNode(NodeLabels.Filesystem);
				node.setProperty("nodeId", autoIncrementServiceImpl.getNextAutoIncrement());
				node.setProperty("filesystemId", filesystemId);
				node.setProperty("version", 0);
				
				for(Entry<String, Object> filesystemPropertiesEntry : filesystemProperties.entrySet())
				{
					node.setProperty(filesystemPropertiesEntry.getKey(), filesystemPropertiesEntry.getValue());
				}
				
				Node rootDirectory = this.graphDatabaseService.createNode(NodeLabels.Directory);
				rootDirectory.setProperty("nodeId", autoIncrementServiceImpl.getNextAutoIncrement());
				
				user.createRelationshipTo(node, RelationshipLabels.has);
				node.createRelationshipTo(rootDirectory, RelationshipLabels.has);
				transaction.success();
			}
		}
	}
	
	@Override
	public void createNewVersion(String userId, String filesystemId, Map<String, Object> changeMetadata, Map<String, Object> changedProperties) throws UserNotFound, FilesystemNotFound
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			CommonCode commonCode = new CommonCode();
			Node filesystem = null;
			try
			{
				filesystem = commonCode.getVersion("filesystem", userId, filesystemId, null, null, -1);
			}
			catch (VersionNotFound | FileNotFound | DirectoryNotFound e) {}
			Node versionedFilesystem = commonCode.copyNodeTree(filesystem);
			
			int filesystemLatestVersion = (int) filesystem.getProperty("version");
			versionedFilesystem.setProperty("version", filesystemLatestVersion + 1);
			for(Entry<String, Object> entry : changedProperties.entrySet())
			{
				versionedFilesystem.setProperty(entry.getKey(), entry.getValue());
			}
			
			Relationship relationship = filesystem.createRelationshipTo(versionedFilesystem, RelationshipLabels.hasVersion);
			for(Entry<String, Object> entry : changeMetadata.entrySet())
			{
				relationship.setProperty(entry.getKey(), entry.getValue());
			}
			
			transaction.success();
		}
	}
	
	@Override
	public void deleteFilesystemTemporarily(String userId, String filesystemId) throws UserNotFound, FilesystemNotFound
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			Node filesystem = new CommonCode().getFilesystem(userId, filesystemId, false);
			Relationship hasRelationship = filesystem.getSingleRelationship(RelationshipLabels.has, Direction.INCOMING);
			Node parentDirectory = hasRelationship.getStartNode();
			
			Relationship hadRelationship = parentDirectory.createRelationshipTo(filesystem, RelationshipLabels.had);
			for(String key : hasRelationship.getPropertyKeys())
			{
				hadRelationship.setProperty(key, hasRelationship.getProperty(key));
			}
			
			hasRelationship.delete();
			transaction.success();
		}
	}
	
	@Override
	public void restoreTemporaryDeletedFilesystem(String userId, String filesystemId) throws UserNotFound, FilesystemNotFound
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			Node filesystem = new CommonCode().getFilesystem(userId, filesystemId, true);
			Relationship hadRelationship = filesystem.getSingleRelationship(RelationshipLabels.had, Direction.INCOMING);
			Node parentDirectory = hadRelationship.getStartNode();
			
			Relationship hasRelationship = parentDirectory.createRelationshipTo(filesystem, RelationshipLabels.has);
			for(String key : hadRelationship.getPropertyKeys())
			{
				hasRelationship.setProperty(key, hadRelationship.getProperty(key));
			}
			
			hadRelationship.delete();
			transaction.success();
		}
	}

	@Override
	public Map<String, Object> getFilesystem(String userId, String filesystemId, int version) throws UserNotFound, FilesystemNotFound, VersionNotFound
	{
		try(Transaction transaction = this.graphDatabaseService.beginTx())
		{
			Node filesystem = null;
			try
			{
				filesystem = new CommonCode().getVersion("filesystem", userId, filesystemId, null, null, version);
			}
			catch (FileNotFound | DirectoryNotFound e) {}
			Map<String, Object> filesystemProperties = new HashMap<String, Object>();
			
			Iterable<String> keys = filesystem.getPropertyKeys();
			for(String key : keys)
			{
				filesystemProperties.put(key, filesystem.getProperty(key));
			}
			
			transaction.success();
			return filesystemProperties;
		}
	}
}
