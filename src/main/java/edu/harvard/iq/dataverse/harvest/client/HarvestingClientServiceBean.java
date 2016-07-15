package edu.harvard.iq.dataverse.harvest.client;

import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.faces.bean.ManagedBean;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.PersistenceContext;

/**
 *
 * @author Leonid Andreev
 * 
 * Dedicated service for managing Harvesting Client Configurations
 */
@Stateless
@Named
public class HarvestingClientServiceBean implements java.io.Serializable {
    @EJB
    DataverseServiceBean dataverseService;
    
    @PersistenceContext(unitName = "VDCNet-ejbPU")
    private EntityManager em;
    
    private static final Logger logger = Logger.getLogger("edu.harvard.iq.dataverse.harvest.client.HarvestingClinetServiceBean");
    
    public HarvestingClient find(Object pk) {
        return (HarvestingClient) em.find(HarvestingClient.class, pk);
    }
    
    public HarvestingClient findByNickname(String nickName) {
        try {
            return em.createNamedQuery("HarvestingClient.findByNickname", HarvestingClient.class)
					.setParameter("nickName", nickName.toLowerCase())
					.getSingleResult();
        } catch ( NoResultException|NonUniqueResultException ex ) {
            logger.fine("Unable to find a single harvesting client by nickname \"" + nickName + "\": " + ex);
            return null;
        }
    }
    
    public List<HarvestingClient> getAllHarvestingClients() {
        try {
            return em.createQuery("SELECT object(c) FROM HarvestingClient AS c ORDER BY c.id").getResultList();
        } catch (Exception ex) {
            logger.warning("Unknown exception caught while looking up configured Harvesting Clients: "+ex.getMessage());
        }
        return null; 
    }
    
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void resetHarvestInProgress(Long hdId) {
        Dataverse hd = em.find(Dataverse.class, hdId);
        em.refresh(hd);
        if (!hd.isHarvested()) {
            return; 
        }
        hd.getHarvestingClientConfig().setHarvestingNow(false);
        
        // And if there is an unfinished RunResult object, we'll
        // just mark it as a failure:
        if (hd.getHarvestingClientConfig().getLastRun() != null 
                && hd.getHarvestingClientConfig().getLastRun().isInProgress()) {
            hd.getHarvestingClientConfig().getLastRun().setFailed();
        }
       
    }
    
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void setHarvestInProgress(Long hdId, Date startTime) {
        Dataverse hd = em.find(Dataverse.class, hdId);
        em.refresh(hd);
        HarvestingClient harvestingClient = hd.getHarvestingClientConfig();
        if (harvestingClient == null) {
            return;
        }
        harvestingClient.setHarvestingNow(false);
        if (harvestingClient.getRunHistory() == null) {
            harvestingClient.setRunHistory(new ArrayList<ClientHarvestRun>());
        }
        ClientHarvestRun currentRun = new ClientHarvestRun();
        currentRun.setHarvestingClient(harvestingClient);
        currentRun.setStartTime(startTime);
        currentRun.setInProgress();
        harvestingClient.getRunHistory().add(currentRun);
    }
    
    
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void setHarvestSuccess(Long hdId, Date currentTime, int harvestedCount, int failedCount) {
        Dataverse hd = em.find(Dataverse.class, hdId);
        em.refresh(hd);
        HarvestingClient harvestingClient = hd.getHarvestingClientConfig();
        if (harvestingClient == null) {
            return;
        }
        
        ClientHarvestRun currentRun = harvestingClient.getLastRun();
        
        if (currentRun != null && currentRun.isInProgress()) {
            // TODO: what if there's no current run in progress? should we just
            // give up quietly, or should we make a noise of some kind? -- L.A. 4.4      
            
            currentRun.setSuccess();
            currentRun.setFinishTime(currentTime);
            currentRun.setHarvestedDatasetCount(new Long(harvestedCount));
            currentRun.setFailedDatasetCount(new Long(failedCount));

            /*TODO: still need to record the number of deleted datasets! */
        }
    }

    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void setHarvestFailure(Long hdId, Date currentTime) {
        Dataverse hd = em.find(Dataverse.class, hdId);
        em.refresh(hd);
        HarvestingClient harvestingClient = hd.getHarvestingClientConfig();
        if (harvestingClient == null) {
            return;
        }
        
        ClientHarvestRun currentRun = harvestingClient.getLastRun();
        
        if (currentRun != null && currentRun.isInProgress()) {
            // TODO: what if there's no current run in progress? should we just
            // give up quietly, or should we make a noise of some kind? -- L.A. 4.4      
            
            currentRun.setFailed();
            currentRun.setFinishTime(currentTime);
        }
    }  
    
    public Long getNumberOfHarvestedDatasetByClients(List<HarvestingClient> clients) {
        String dvs = null; 
        for (HarvestingClient client: clients) {
            if (dvs == null) {
                dvs = client.getDataverse().getId().toString();
            } else {
                dvs = dvs.concat(","+client.getDataverse().getId().toString());
            }
        }
        
        try {
            return (Long) em.createNativeQuery("SELECT count(d.id) FROM dataset d, "
                    + " dvobject o WHERE d.id = o.id AND o.owner_id in (" 
                    + dvs + ")").getSingleResult();

        } catch (Exception ex) {
            logger.info("Warning: exception trying to count harvested datasets by clients: " + ex.getMessage());
            return 0L;
        }
    }
}