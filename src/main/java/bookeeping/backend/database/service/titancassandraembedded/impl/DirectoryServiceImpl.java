package bookeeping.backend.database.service.titancassandraembedded.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import bookeeping.backend.database.MandatoryProperties;
import bookeeping.backend.database.connection.singleton.TitanCassandraEmbeddedConnection;
import bookeeping.backend.database.service.DirectoryService;
import bookeeping.backend.database.titan.NodeLabels;
import bookeeping.backend.database.titan.RelationshipLabels;
import bookeeping.backend.exception.DirectoryNotFound;
import bookeeping.backend.exception.DuplicateDirectory;
import bookeeping.backend.exception.FileNotFound;
import bookeeping.backend.exception.FilesystemNotFound;
import bookeeping.backend.exception.UserNotFound;
import bookeeping.backend.exception.VersionNotFound;

import com.thinkaurelius.titan.core.TitanGraph;
import com.thinkaurelius.titan.core.TitanTransaction;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;

public class DirectoryServiceImpl implements DirectoryService
{
	private TitanGraph titanGraph;
	
	public DirectoryServiceImpl()
	{
		this.titanGraph = TitanCassandraEmbeddedConnection.getInstance().getTitanGraphObject();
	}
	
	@Override
	public void createNewDirectory(String userId, String filesystemId, String filesystemVersion, String directoryPath, String directoryName, Map<String, Object> directoryProperties) throws UserNotFound, FilesystemNotFound, DuplicateDirectory
	{
		TitanTransaction titanTransaction = this.titanGraph.newTransaction();
		
		try
		{
			CommonCode commonCode = new CommonCode();
			try
			{
				commonCode.getDirectory(userId, filesystemId, directoryPath, directoryName, false, null);
				throw new DuplicateDirectory("ERROR: Directory already present! - \"" + directoryPath + "/" + directoryName + "\"");
			}
			catch(DirectoryNotFound directoryNotFound)
			{
				Vertex node = this.titanGraph.addVertexWithLabel(NodeLabels.Directory.name());
				node.setProperty(MandatoryProperties.nodeId.name(), new AutoIncrementServiceImpl().getNextAutoIncrement());
				node.setProperty(MandatoryProperties.directoryPath.name(), directoryPath);
				node.setProperty(MandatoryProperties.directoryName.name(), directoryName);
				node.setProperty(MandatoryProperties.version.name(), 0);
				
				for(Entry<String, Object> directoryPropertiesEntry : directoryProperties.entrySet())
				{
					node.setProperty(directoryPropertiesEntry.getKey(), directoryPropertiesEntry.getValue());
				}
				
				Vertex rootDirectory = commonCode.getRootDirectory(userId, filesystemId);
				rootDirectory.addEdge(RelationshipLabels.has.name(), node).setProperty(MandatoryProperties.commitId.name(), filesystemVersion);
				titanTransaction.commit();
			}
		}
		finally
		{
			if(titanTransaction.isOpen())
			{
				titanTransaction.rollback();
			}
		}
	}

	@Override
	public void createNewVersion(String userId, String filesystemId, String filesystemVersion, String directoryPath, String directoryName, Map<String, Object> changeMetadata, Map<String, Object> changedProperties) throws UserNotFound, FilesystemNotFound, DirectoryNotFound
	{
		TitanTransaction titanTransaction = this.titanGraph.newTransaction();
		
		try
		{
			CommonCode commonCode = new CommonCode();
			Vertex directory = null;
			try
			{
				directory = commonCode.getVersion("directory", userId, filesystemId, directoryPath, directoryName, -1, false, null);
			}
			catch (VersionNotFound | FileNotFound e) {}
			Vertex versionedDirectory = commonCode.copyNode(directory);
			
			int directoryLatestVersion = (int) directory.getProperty(MandatoryProperties.version.name());
			versionedDirectory.setProperty(MandatoryProperties.version.name(), directoryLatestVersion + 1);
			for(Entry<String, Object> entry : changedProperties.entrySet())
			{
				versionedDirectory.setProperty(entry.getKey(), entry.getValue());
			}
			versionedDirectory.removeProperty(MandatoryProperties.directoryPath.name());
			versionedDirectory.removeProperty(MandatoryProperties.directoryName.name());
			
			Edge relationship = directory.addEdge(RelationshipLabels.hasVersion.name(), versionedDirectory);
			for(Entry<String, Object> entry : changeMetadata.entrySet())
			{
				relationship.setProperty(entry.getKey(), entry.getValue());
			}
			relationship.setProperty(MandatoryProperties.commitId.name(), filesystemVersion);
			
			titanTransaction.commit();
		}
		finally
		{
			if(titanTransaction.isOpen())
			{
				titanTransaction.rollback();
			}
		}
	}

	@Override
	public void deleteDirectoryTemporarily(String userId, String filesystemId, String filesystemVersion, String directoryPath, String directoryName) throws UserNotFound, FilesystemNotFound, DirectoryNotFound
	{
		TitanTransaction titanTransaction = this.titanGraph.newTransaction();
		
		try
		{
			Vertex directory = new CommonCode().getDirectory(userId, filesystemId, directoryPath, directoryName, false, null);
			Edge hasRelationship = directory.getEdges(Direction.IN, RelationshipLabels.has.name()).iterator().next();
			Vertex parentDirectory = hasRelationship.getVertex(Direction.OUT);
			
			Edge hadRelationship = parentDirectory.addEdge(RelationshipLabels.had.name(), directory);
			for(String key : hasRelationship.getPropertyKeys())
			{
				hadRelationship.setProperty(key, hasRelationship.getProperty(key));
			}
			hadRelationship.setProperty(MandatoryProperties.commitId.name(), filesystemVersion);
			
			hasRelationship.remove();
			titanTransaction.commit();
		}
		finally
		{
			if(titanTransaction.isOpen())
			{
				titanTransaction.rollback();
			}
		}
	}
	
	@Override
	public void restoreTemporaryDeletedDirectory(String userId, String filesystemId, String filesystemVersion, String directoryPath, String directoryName, String previousfilesystemVersion) throws UserNotFound, FilesystemNotFound, DirectoryNotFound, DuplicateDirectory
	{
		TitanTransaction titanTransaction = this.titanGraph.newTransaction();
		
		try
		{
			CommonCode commonCode = new CommonCode();
			try
			{
				commonCode.getDirectory(userId, filesystemId, directoryPath, directoryName, false, null);
				throw new DuplicateDirectory("ERROR: Directory already present! - \"" + directoryPath + "/" + directoryName + "\"");
			}
			catch(DirectoryNotFound directoryNotFound)
			{
				Vertex directory = commonCode.getDirectory(userId, filesystemId, directoryPath, directoryName, true, previousfilesystemVersion);
				Edge hadRelationship = directory.getEdges(Direction.IN, RelationshipLabels.had.name()).iterator().next();
				Vertex parentDirectory = hadRelationship.getVertex(Direction.OUT);
				
				Edge hasRelationship = parentDirectory.addEdge(RelationshipLabels.has.name(), directory);
				for(String key : hadRelationship.getPropertyKeys())
				{
					hasRelationship.setProperty(key, hadRelationship.getProperty(key));
				}
				hasRelationship.setProperty(MandatoryProperties.commitId.name(), filesystemVersion);
				
				hadRelationship.remove();
				titanTransaction.commit();
			}
		}
		finally
		{
			if(titanTransaction.isOpen())
			{
				titanTransaction.rollback();
			}
		}
	}
	
	@Override
	public void moveDirectory(String userId, String filesystemId, String filesystemVersion, String oldDirectoryPath, String oldDirectoryName, String newDirectoryPath, String newDirectoryName) throws UserNotFound, FilesystemNotFound, DirectoryNotFound, DuplicateDirectory
	{
		TitanTransaction titanTransaction = this.titanGraph.newTransaction();
		
		try
		{
			Map<String, Object> directoryProperties = null;
			try
			{
				directoryProperties = this.getDirectory(userId, filesystemId, oldDirectoryPath, oldDirectoryName, -1);
				directoryProperties.remove(MandatoryProperties.nodeId.name());
				directoryProperties.remove(MandatoryProperties.directoryPath.name());
				directoryProperties.remove(MandatoryProperties.directoryName.name());
				directoryProperties.remove(MandatoryProperties.version.name());
			}
			catch (VersionNotFound e) {}
			
			this.deleteDirectoryTemporarily(filesystemVersion, userId, filesystemId, oldDirectoryPath, oldDirectoryName);
			this.createNewDirectory(filesystemVersion, newDirectoryPath, newDirectoryName, filesystemId, userId, directoryProperties);
			
			CommonCode commonCode = new CommonCode();
			Vertex oldDirectory = commonCode.getDirectory(userId, filesystemId, oldDirectoryPath, oldDirectoryName, true, filesystemVersion);
			Vertex newDirectory = commonCode.getDirectory(userId, filesystemId, oldDirectoryPath, oldDirectoryName, false, null);
			for(Edge oldRelationship : oldDirectory.getEdges(Direction.OUT, RelationshipLabels.has.name()))
			{
				Vertex endNode = oldRelationship.getVertex(Direction.IN);
				endNode.setProperty(MandatoryProperties.filePath.name(), newDirectoryPath + "/" + newDirectoryName);
				Edge newRelationship = newDirectory.addEdge(oldRelationship.getLabel(), endNode);
				
				for(String key : oldRelationship.getPropertyKeys())
				{
					newRelationship.setProperty(key, oldRelationship.getProperty(key));
				}
				
				oldRelationship.remove();
			}
			for(Edge oldRelationship : oldDirectory.getEdges(Direction.OUT, RelationshipLabels.had.name()))
			{
				Vertex endNode = oldRelationship.getVertex(Direction.IN);
				endNode.setProperty(MandatoryProperties.filePath.name(), newDirectoryPath + "/" + newDirectoryName);
				Edge newRelationship = newDirectory.addEdge(oldRelationship.getLabel(), endNode);
				
				for(String key : oldRelationship.getPropertyKeys())
				{
					newRelationship.setProperty(key, oldRelationship.getProperty(key));
				}
				
				oldRelationship.remove();
			}
			
			titanTransaction.commit();
		}
		finally
		{
			if(titanTransaction.isOpen())
			{
				titanTransaction.rollback();
			}
		}
	}

	@Override
	public Map<String, Object> getDirectory(String userId, String filesystemId, String directoryPath, String directoryName, int version) throws UserNotFound, FilesystemNotFound, DirectoryNotFound, VersionNotFound
	{
		TitanTransaction titanTransaction = this.titanGraph.newTransaction();
		
		try
		{
			Vertex directory = null;
			try
			{
				directory = new CommonCode().getVersion("directory", userId, filesystemId, directoryPath, directoryName, version, false, null);
			}
			catch (FileNotFound e) {}
			Map<String, Object> directoryProperties = new HashMap<String, Object>();
			
			Iterable<String> keys = directory.getPropertyKeys();
			for(String key : keys)
			{
				directoryProperties.put(key, directory.getProperty(key));
			}
			
			titanTransaction.commit();
			return directoryProperties;
		}
		finally
		{
			if(titanTransaction.isOpen())
			{
				titanTransaction.rollback();
			}
		}
	}
}
