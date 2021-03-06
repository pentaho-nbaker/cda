/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */
package pt.webdetails.cda.dataaccess;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.table.TableModel;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Element;
import org.pentaho.platform.api.engine.ObjectFactoryException;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalog;
import org.pentaho.platform.plugin.action.mondrian.catalog.MondrianCatalogHelper;
import org.pentaho.reporting.engine.classic.core.DataFactory;
import org.pentaho.reporting.engine.classic.core.ParameterDataRow;
import org.pentaho.reporting.engine.classic.extensions.datasources.mondrian.AbstractNamedMDXDataFactory;
import org.pentaho.reporting.engine.classic.extensions.datasources.mondrian.BandedMDXDataFactory;
import org.pentaho.reporting.engine.classic.extensions.datasources.mondrian.DefaultCubeFileProvider;
import org.pentaho.reporting.engine.classic.extensions.datasources.mondrian.MondrianConnectionProvider;
import org.pentaho.reporting.libraries.base.util.IOUtils;
import org.pentaho.reporting.platform.plugin.connection.PentahoCubeFileProvider;
import org.pentaho.reporting.platform.plugin.connection.PentahoMondrianConnectionProvider;
import pt.webdetails.cda.CdaBoot;
import pt.webdetails.cda.CdaEngine;
import pt.webdetails.cda.connections.ConnectionCatalog.ConnectionType;
import pt.webdetails.cda.connections.InvalidConnectionException;
import pt.webdetails.cda.connections.mondrian.AbstractMondrianConnection;
import pt.webdetails.cda.connections.mondrian.MondrianConnection;
import pt.webdetails.cda.connections.mondrian.MondrianConnectionInfo;
import pt.webdetails.cda.settings.UnknownConnectionException;
import pt.webdetails.cda.utils.mondrian.CompactBandedMDXDataFactory;

/**
 * Implementation of a DataAccess that will get data from a SQL database
 * <p/>
 * User: pedro Date: Feb 3, 2010 Time: 12:18:05 PM
 */
public class MdxDataAccess extends PREDataAccess
{

  private static final Log logger = LogFactory.getLog(MdxDataAccess.class);

  public enum BANDED_MODE
  {

    CLASSIC, COMPACT
  };
  private BANDED_MODE bandedMode = BANDED_MODE.CLASSIC;


  /**
   *
   * @param id
   * @param name
   * @param connectionId
   * @param query
   */
  public MdxDataAccess(String id, String name, String connectionId, String query)
  {
    super(id, name, connectionId, query);
    try
    {
      String _mode = CdaBoot.getInstance().getGlobalConfig().getConfigProperty("pt.webdetails.cda.BandedMDXMode");
      if (_mode != null)
      {
        bandedMode = BANDED_MODE.valueOf(_mode);
      }
    }
    catch (Exception ex)
    {
      bandedMode = BANDED_MODE.COMPACT;
    }
  }


  public MdxDataAccess(final Element element)
  {
    super(element);

    try
    {
      bandedMode = BANDED_MODE.valueOf(element.selectSingleNode("./BandedMode").getText().toUpperCase());

    }
    catch (Exception e)
    {
      // Getting defaults
      try
      {
        String _mode = CdaBoot.getInstance().getGlobalConfig().getConfigProperty("pt.webdetails.cda.BandedMDXMode");
        if (_mode != null)
        {
          bandedMode = BANDED_MODE.valueOf(_mode);
        }
      }
      catch (Exception ex)
      {
        // ignore, let the default take it's place
      }

    }


  }


  public MdxDataAccess()
  {
  }


  protected AbstractNamedMDXDataFactory createDataFactory()
  {
    if (getBandedMode() == BANDED_MODE.CLASSIC)
    {
      return new BandedMDXDataFactory();

    }
    else
    {
      return new CompactBandedMDXDataFactory();
    }
  }


  @Override
  public DataFactory getDataFactory() throws UnknownConnectionException, InvalidConnectionException
  {

    logger.debug("Creating BandedMDXDataFactory");

    final MondrianConnection connection = (MondrianConnection) getCdaSettings().getConnection(getConnectionId());
    final MondrianConnectionInfo mondrianConnectionInfo = connection.getConnectionInfo();

    final AbstractNamedMDXDataFactory mdxDataFactory = createDataFactory();
    setMdxDataFactoryBaseConnectionProperties(connection, mdxDataFactory);
    
    mdxDataFactory.setDataSourceProvider(connection.getInitializedDataSourceProvider());
    mdxDataFactory.setJdbcPassword(mondrianConnectionInfo.getPass());
    mdxDataFactory.setJdbcUser(mondrianConnectionInfo.getUser());
    mdxDataFactory.setRole(mondrianConnectionInfo.getMondrianRole());
    mdxDataFactory.setRoleField(mondrianConnectionInfo.getRoleField());
    mdxDataFactory.setJdbcPasswordField(mondrianConnectionInfo.getPasswordField());
    mdxDataFactory.setJdbcUserField(mondrianConnectionInfo.getUserField());

    if (CdaEngine.getInstance().isStandalone())
    {
      mdxDataFactory.setCubeFileProvider(new DefaultCubeFileProvider(mondrianConnectionInfo.getCatalog()));
    }
    else
    {
      mdxDataFactory.setCubeFileProvider(new PentahoCubeFileProvider(mondrianConnectionInfo.getCatalog()));
      try
      {
        mdxDataFactory.setMondrianConnectionProvider((MondrianConnectionProvider) PentahoSystem.getObjectFactory().get(PentahoMondrianConnectionProvider.class, "MondrianConnectionProvider", null));
      }
      catch (ObjectFactoryException e)
      {//couldn't get object
        mdxDataFactory.setMondrianConnectionProvider(new PentahoMondrianConnectionProvider());
      }
    }

    // using deprecated method for 3.10 support
    mdxDataFactory.setQuery("query", getQuery());

    return mdxDataFactory;


  }


  /**
   * Method to initialize MdxDataFactory's base connection properties.
   * This has to be done in order to pass the extra set of properties that mondrian
   * needs in order to share cache. This work should be done by the reporting plugin, 
   * but it's not, so we do it in here.
   * 
   * The mdxDataFactory.setBaseConnectionProperties method only exists in PRD 3.9.1
   * (from pentaho 4.8), so we're gonna call it by reflection. If it fails due to
   * an older version of the platform, it will still work but no extra properties 
   * will be passed to mondrian
   * 
   * @param mdxDataFactory 
   */
  private void setMdxDataFactoryBaseConnectionProperties(MondrianConnection connection, AbstractNamedMDXDataFactory mdxDataFactory)
  {
    
    
    if (!CdaEngine.getInstance().isStandalone())
    {
      
      
      PentahoCubeFileProvider cubeProvider = new PentahoCubeFileProvider(connection.getConnectionInfo().getCatalog());
      

      final List<MondrianCatalog> catalogs =
              MondrianCatalogHelper.getInstance().listCatalogs(PentahoSessionHolder.getSession(), false);

      MondrianCatalog catalog = null;
      for (MondrianCatalog cat : catalogs)
      {
        final String definition = cat.getDefinition();
        final String definitionFileName = IOUtils.getInstance().getFileName(definition);
        if (definitionFileName.equals(IOUtils.getInstance().getFileName(connection.getConnectionInfo().getCatalog())))
        {
          catalog = cat;
          break;
        }
      }
      
      if ( catalog != null){
        
        Properties props = new Properties();
        try
        {
          props.load(new StringReader(catalog.getDataSourceInfo().replace(';', '\n')));
          try
          {
            // Apply the method through reflection
            Method m = AbstractNamedMDXDataFactory.class.getMethod("setBaseConnectionProperties",Properties.class);
            m.invoke(mdxDataFactory, props);
            
          }
          catch (Exception ex)
          {
            // This is a previous version - continue
          }
          
          
        }
        catch (IOException ex)
        {
          logger.warn("Failed to transform DataSourceInfo string '"+ catalog.getDataSourceInfo() +"' into properties");
        }
        
      }
      
      
    }
    
    
    
  }


  public BANDED_MODE getBandedMode()
  {
    return bandedMode;
  }


  public String getType()
  {
    return "mdx";
  }


  @Override
  public ConnectionType getConnectionType()
  {
    return ConnectionType.MDX;
  }


  @Override
  protected TableModel postProcessTableModel(TableModel tm)
  {
//    logger.debug("Determining if we need to transform the BandedMDXTableModel");
//
//    
//    if( tm instanceof BandedMDXDataFactory){
//    BandedMDXDataFactory df = (BandedMDXDataFactory) tm;
//    TableModel t = new CompactBandedMDXTableModel(df. (df., rowLimit)
//    }

    return tm;
  }


  @Override
  protected Serializable getExtraCacheKey()
  {//TODO: is this necessary after role assembly in EvaluableConnection.evaluate()? 
    MondrianConnectionInfo mci;
    try
    {
      mci = ((AbstractMondrianConnection) getCdaSettings().getConnection(getConnectionId())).getConnectionInfo();
    }
    catch (Exception e)
    {
      logger.error("Failed to get a connection info for cache key");
      mci = null;
    }
    return new ExtraCacheKey(bandedMode, mci.getMondrianRole());
  }

  protected static class ExtraCacheKey implements Serializable
  {

    private static final long serialVersionUID = 1L;
    private BANDED_MODE bandedMode;
    private String roles;


    public ExtraCacheKey(BANDED_MODE bandedMode, String roles)
    {
      this.bandedMode = bandedMode;
      this.roles = roles;
    }


    @Override
    public boolean equals(Object obj)
    {
      if (obj == null)
      {
        return false;
      }
      if (getClass() != obj.getClass())
      {
        return false;
      }
      final ExtraCacheKey other = (ExtraCacheKey) obj;
      if (this.bandedMode != other.bandedMode && (this.bandedMode == null || !this.bandedMode.equals(other.bandedMode)))
      {
        return false;
      }
      else if (this.roles == null ? other.roles != null : !this.roles.equals(other.roles))
      {
        return false;
      }
      return true;
    }


    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException
    {
      this.bandedMode = (BANDED_MODE) in.readObject();
      this.roles = (String) in.readObject();
    }


    private void writeObject(java.io.ObjectOutputStream out) throws IOException
    {
      out.writeObject(this.bandedMode);
      out.writeObject(this.roles);
    }


    @Override
    public int hashCode()
    {
      int hash = 7;
      hash = 83 * hash + (this.bandedMode != null ? this.bandedMode.hashCode() : 0);
      hash = 83 * hash + (this.roles != null ? this.roles.hashCode() : 0);
      return hash;
    }


    @Override
    public String toString()
    {
      return this.getClass().getName() + "[bandedMode: " + bandedMode + "; roles:" + roles + "]";
    }
  }


  @Override
  public ArrayList<PropertyDescriptor> getInterface()
  {
    ArrayList<PropertyDescriptor> properties = super.getInterface();
    properties.add(new PropertyDescriptor("bandedMode", PropertyDescriptor.Type.STRING, PropertyDescriptor.Placement.CHILD));
    return properties;
  }

  //treat special cases: allow string[]

  @Override
  protected IDataSourceQuery performRawQuery(final ParameterDataRow parameterDataRow) throws QueryException
  {
    final String MDX_MULTI_SEPARATOR = ",";

    String[] columnNames = parameterDataRow.getColumnNames();
    Object[] values = new Object[columnNames.length];

    for (int i = 0; i < columnNames.length; i++)
    {
      String colName = columnNames[i];
      Object value = parameterDataRow.get(colName);
      if (value != null && value.getClass().isArray())
      {
        //translate value
        value = StringUtils.join((Object[]) value, MDX_MULTI_SEPARATOR);
      }
      values[i] = value;
    }

    return super.performRawQuery(new ParameterDataRow(columnNames, values));

  }
}
