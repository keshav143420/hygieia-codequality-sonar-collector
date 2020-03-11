package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

@Component
public class SonarSecurityCollectorTask extends CollectorTask<SonarSecurityCollector> {

    private static final Log LOG = LogFactory.getLog(SonarSecurityCollectorTask.class);

    private final SonarSecurityCollectorRepository sonarSecurityCollectorRepository;
    private final SonarProjectRepository sonarProjectRepository;
    private final CodeQualityRepository codeQualityRepository;
    private final SonarProfileRepostory sonarProfileRepostory;
    private final SonarClientSelector sonarClientSelector;
    private final SonarSettings sonarSettings;
    private final ComponentRepository dbComponentRepository;
    private final ConfigurationRepository configurationRepository;

    @Autowired
    public SonarSecurityCollectorTask(TaskScheduler taskScheduler,
                                      SonarSecurityCollectorRepository sonarSecurityCollectorRepository,
                                      SonarProjectRepository sonarProjectRepository,
                                      CodeQualityRepository codeQualityRepository,
                                      SonarProfileRepostory sonarProfileRepostory,
                                      SonarSettings sonarSettings,
                                      SonarClientSelector sonarClientSelector,
                                      ConfigurationRepository configurationRepository,
                                      ComponentRepository dbComponentRepository) {
        super(taskScheduler, "SonarSecurity");
        this.sonarSecurityCollectorRepository = sonarSecurityCollectorRepository;
        this.sonarProjectRepository = sonarProjectRepository;
        this.codeQualityRepository = codeQualityRepository;
        this.sonarProfileRepostory = sonarProfileRepostory;
        this.sonarSettings = sonarSettings;
        this.sonarClientSelector = sonarClientSelector;
        this.dbComponentRepository = dbComponentRepository;
        this.configurationRepository = configurationRepository;
    }

    @Override
    public SonarSecurityCollector getCollector() {

        Configuration config = configurationRepository.findByCollectorName("SonarSecurity");
        // Only use Admin Page server configuration when available
        // otherwise use properties file server configuration
        if (config != null) {
            config.decryptOrEncrptInfo();
            // To clear the username and password from existing run and
            // pick the latest
            sonarSettings.getServers().clear();
            sonarSettings.getUsernames().clear();
            sonarSettings.getPasswords().clear();
            for (Map<String, String> sonarServer : config.getInfo()) {
                sonarSettings.getServers().add(sonarServer.get("url"));
                sonarSettings.getUsernames().add(sonarServer.get("userName"));
                sonarSettings.getPasswords().add(sonarServer.get("password"));
            }
        }

        return SonarSecurityCollector.prototype(sonarSettings.getServers(),  sonarSettings.getNiceNames());
    }

    @Override
    public BaseCollectorRepository<SonarSecurityCollector> getCollectorRepository() {
        return sonarSecurityCollectorRepository;
    }

    @Override
    public String getCron() {
        return sonarSettings.getCron();
    }

    @Override
    public void collect(SonarSecurityCollector collector) {
        long start = System.currentTimeMillis();

        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        List<SonarProject> existingProjects = sonarProjectRepository.findByCollectorIdIn(udId);
        List<SonarProject> latestProjects = new ArrayList<>();
        clean(collector, existingProjects);

        if (!CollectionUtils.isEmpty(collector.getSonarServers())) {

            for (int i = 0; i < collector.getSonarServers().size(); i++) {

                String instanceUrl = collector.getSonarServers().get(i);
                logBanner(instanceUrl);

                Double version = sonarClientSelector.getSonarVersion(instanceUrl);
                SonarClient sonarClient = sonarClientSelector.getSonarClient(version);

                String username = getFromListSafely(sonarSettings.getUsernames(), i);
                String password = getFromListSafely(sonarSettings.getPasswords(), i);
                String token = getFromListSafely(sonarSettings.getTokens(), i);
                sonarClient.setServerCredentials(username, password, token);

                List<SonarProject> projects = sonarClient.getProjects(instanceUrl);
                latestProjects.addAll(projects);

                int projSize = CollectionUtils.size(projects);
                log("Fetched projects   " + projSize, start);

                addNewProjects(projects, existingProjects, collector);

                refreshData(enabledProjects(collector, instanceUrl), sonarClient);

                // Changelog apis do not exist for sonarqube versions under version 5.0
                if (version >= 5.0) {
                  try {
                     fetchQualityProfileConfigChanges(collector,instanceUrl,sonarClient);
                   } catch (Exception e) {
                     LOG.error(e);
                    }
                }

                log("Finished", start);
            }
        }
        deleteUnwantedJobs(latestProjects, existingProjects, collector);
    }

    private String getFromListSafely(List<String> ls, int index){
        if(CollectionUtils.isEmpty(ls)) {
            return null;
        } else if (ls.size() > index){
            return ls.get(index);
        }
        return null;
    }
	/**
	 * Clean up unused sonar collector items
	 *
     * @param collector
     *            the {@link SonarCollector}
     */
    private void clean(SonarSecurityCollector collector, List<SonarProject> existingProjects) {
        // extract unique collector item IDs from components
        // (in this context collector_items are sonar projects)
        Set<ObjectId> uniqueIDs = StreamSupport.stream(dbComponentRepository.findAll().spliterator(),false)
            .filter( comp -> comp.getCollectorItems() != null && !comp.getCollectorItems().isEmpty())
            .map(comp -> comp.getCollectorItems().get(CollectorType.StaticSecurityScan))
            // keep nonNull List<CollectorItem>
            .filter(Objects::nonNull)
            // merge all lists (flatten) into a stream
            .flatMap(List::stream)
            // keep nonNull CollectorItems
            .filter(ci -> ci != null && ci.getCollectorId().equals(collector.getId()))
            .map(CollectorItem::getId)
            .collect(Collectors.toSet());

        List<SonarProject> stateChangeJobList = new ArrayList<>();

        for (SonarProject job : existingProjects) {
            // collect the jobs that need to change state : enabled vs disabled.
            if ((job.isEnabled() && !uniqueIDs.contains(job.getId())) ||  // if it was enabled but not on a dashboard
                    (!job.isEnabled() && uniqueIDs.contains(job.getId()))) { // OR it was disabled and now on a dashboard
                job.setEnabled(uniqueIDs.contains(job.getId()));
                stateChangeJobList.add(job);
            }
        }
        if (!CollectionUtils.isEmpty(stateChangeJobList)) {
            sonarProjectRepository.save(stateChangeJobList);
        }
    }


    private void deleteUnwantedJobs(List<SonarProject> latestProjects, List<SonarProject> existingProjects, SonarSecurityCollector collector) {
        List<SonarProject> deleteJobList = new ArrayList<>();

        // First delete collector items that are not supposed to be collected anymore because the servers have moved(?)
        for (SonarProject job : existingProjects) {
            if (job.isPushed()) continue; // do not delete jobs that are being pushed via API
            if (!collector.getSonarServers().contains(job.getInstanceUrl()) ||
                    (!job.getCollectorId().equals(collector.getId())) ||
                    (!latestProjects.contains(job))) {
                if(!job.isEnabled()) {
                    LOG.debug("drop deleted sonar project which is disabled "+job.getProjectName());
                    deleteJobList.add(job);
                } else {
                    LOG.debug("drop deleted sonar project which is enabled "+job.getProjectName());
                    // CollectorItem should be removed from components and dashboards first
                    // then the CollectorItem (sonar proj in this case) can be deleted

                    List<com.capitalone.dashboard.model.Component> comps =
                            dbComponentRepository
                        .findByCollectorTypeAndItemIdIn(CollectorType.StaticSecurityScan, Collections.singletonList(job.getId()));

                    for (com.capitalone.dashboard.model.Component c: comps) {
                        c.getCollectorItems().get(CollectorType.StaticSecurityScan).removeIf(collectorItem -> collectorItem.getId().equals(job.getId()));
                        if(CollectionUtils.isEmpty(c.getCollectorItems().get(CollectorType.StaticSecurityScan))){
                            c.getCollectorItems().remove(CollectorType.StaticSecurityScan);
                        }
                    }
                    dbComponentRepository.save(comps);

                    // other collectors also delete the widget but not here
                    // should not remove the code analysis widget
                    // because it is shared by other collectors

                    deleteJobList.add(job);
                }
            }
        }
        if (!CollectionUtils.isEmpty(deleteJobList)) {
            sonarProjectRepository.delete(deleteJobList);
        }
    }

    private void refreshData(List<SonarProject> sonarProjects, SonarClient sonarClient) {
        long start = System.currentTimeMillis();
        int count = 0;

        for (SonarProject project : sonarProjects) {
            CodeQuality codeQuality = sonarClient.currentSecurityCodeQuality(project);
            if (codeQuality != null && isNewQualityData(project, codeQuality)) {
                project.setLastUpdated(System.currentTimeMillis());
                sonarProjectRepository.save(project);
                codeQuality.setCollectorItemId(project.getId());
                codeQualityRepository.save(codeQuality);
                count++;
            }
        }
        log("Updated", start, count);
    }

    private void fetchQualityProfileConfigChanges(SonarSecurityCollector collector, String instanceUrl, SonarClient sonarClient) throws org.json.simple.parser.ParseException{
    	JSONArray qualityProfiles = sonarClient.getQualityProfiles(instanceUrl);
    	JSONArray sonarProfileConfigurationChanges = new JSONArray();

    	for (Object qualityProfile : qualityProfiles ) {
    		JSONObject qualityProfileJson = (JSONObject) qualityProfile;
    		String qualityProfileKey = (String)qualityProfileJson.get("key");

    		List<String> sonarProjects = sonarClient.retrieveProfileAndProjectAssociation(instanceUrl,qualityProfileKey);
    		if (sonarProjects != null){
    			sonarProfileConfigurationChanges = sonarClient.getQualityProfileConfigurationChanges(instanceUrl,qualityProfileKey);
    			addNewConfigurationChanges(collector,sonarProfileConfigurationChanges);
    		}
    	}
    }

    private void addNewConfigurationChanges(SonarSecurityCollector collector, JSONArray sonarProfileConfigurationChanges){
    	ArrayList<CollectorItemConfigHistory> profileConfigChanges = new ArrayList<>();

    	for (Object configChange : sonarProfileConfigurationChanges) {
    		JSONObject configChangeJson = (JSONObject) configChange;
    		CollectorItemConfigHistory profileConfigChange = new CollectorItemConfigHistory();
    		Map<String,Object> changeMap = new HashMap<>();

    		profileConfigChange.setCollectorItemId(collector.getId());
    		profileConfigChange.setUserName((String) configChangeJson.get("authorName"));
    		profileConfigChange.setUserID((String) configChangeJson.get("authorLogin") );
    		changeMap.put("event", configChangeJson);

    		profileConfigChange.setChangeMap(changeMap);

    		ConfigHistOperationType operation = determineConfigChangeOperationType((String)configChangeJson.get("action"));
    		profileConfigChange.setOperation(operation);


    		long timestamp = convertToTimestamp((String) configChangeJson.get("date"));
    		profileConfigChange.setTimestamp(timestamp);

    		if (isNewConfig(collector.getId(),(String) configChangeJson.get("authorLogin"),operation,timestamp)) {
    			profileConfigChanges.add(profileConfigChange);
    		}
    	}
    	sonarProfileRepostory.save(profileConfigChanges);
    }

    private Boolean isNewConfig(ObjectId collectorId,String authorLogin,ConfigHistOperationType operation,long timestamp) {
    	List<CollectorItemConfigHistory> storedConfigs = sonarProfileRepostory.findProfileConfigChanges(collectorId, authorLogin,operation,timestamp);
    	return storedConfigs.isEmpty();
    }

    private List<SonarProject> enabledProjects(SonarSecurityCollector collector, String instanceUrl) {
        return sonarProjectRepository.findEnabledProjects(collector.getId(), instanceUrl);
    }

    private void addNewProjects(List<SonarProject> projects, List<SonarProject> existingProjects, SonarSecurityCollector collector) {
        long start = System.currentTimeMillis();
        int count = 0;
        List<SonarProject> newProjects = new ArrayList<>();
        List<SonarProject> updateProjects = new ArrayList<>();
        for (SonarProject project : projects) {
            String niceName = getNiceName(project,collector);
            if (!existingProjects.contains(project)) {
                project.setCollectorId(collector.getId());
                project.setEnabled(false);
                project.setDescription(project.getProjectName());
                project.setNiceName(niceName);
                newProjects.add(project);
                count++;
            }else{
                if(CollectionUtils.isNotEmpty(existingProjects)){
                    int[] indexes = IntStream.range(0,existingProjects.size()).filter(i-> existingProjects.get(i).equals(project)).toArray();
                    for (int index :indexes) {
                        SonarProject s = existingProjects.get(index);
                        s.setProjectId(project.getProjectId());
                        if(StringUtils.isEmpty(s.getNiceName())){
                            s.setNiceName(niceName);
                        }
                        updateProjects.add(s);
                    }
                }
            }
        }
        //save all in one shot
        if (!CollectionUtils.isEmpty(newProjects)) {
            sonarProjectRepository.save(newProjects);
        }
        if (!CollectionUtils.isEmpty(updateProjects)) {
            sonarProjectRepository.save(updateProjects);
        }
        log("New projects", start, count);
    }

    private String getNiceName(SonarProject project, SonarSecurityCollector sonarCollector){

        if (org.springframework.util.CollectionUtils.isEmpty(sonarCollector.getSonarServers())) return "";
        List<String> servers = sonarCollector.getSonarServers();
        List<String> niceNames = sonarCollector.getNiceNames();
        if (org.springframework.util.CollectionUtils.isEmpty(niceNames)) return "";
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(project.getInstanceUrl()) && (niceNames.size() > i)) {
                return niceNames.get(i);
            }
        }
        return "";

    }

    @SuppressWarnings("unused")
	private boolean isNewProject(SonarCollector collector, SonarProject application) {
        return sonarProjectRepository.findSonarProject(
                collector.getId(), application.getInstanceUrl(), application.getProjectId()) == null;
    }

    private boolean isNewQualityData(SonarProject project, CodeQuality codeQuality) {
        return codeQualityRepository.findByCollectorItemIdAndTimestamp(
                project.getId(), codeQuality.getTimestamp()) == null;
    }

    private long convertToTimestamp(String date) {

    	DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    	DateTime dt = formatter.parseDateTime(date);

        return new DateTime(dt).getMillis();
    }

    private ConfigHistOperationType determineConfigChangeOperationType(String changeAction){
    	switch (changeAction) {

	    	case "DEACTIVATED":
	    		return ConfigHistOperationType.DELETED;

	    	case "ACTIVATED":
	    		return ConfigHistOperationType.CREATED;
	    	default:
	    		return ConfigHistOperationType.CHANGED;
    	}
    }

}
