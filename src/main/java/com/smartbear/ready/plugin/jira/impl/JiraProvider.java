package com.smartbear.ready.plugin.jira.impl;

import com.atlassian.jira.rest.client.api.GetCreateIssueMetadataOptions;
import com.atlassian.jira.rest.client.api.GetCreateIssueMetadataOptionsBuilder;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.MetadataRestClient;
import com.atlassian.jira.rest.client.api.OptionalIterable;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import com.atlassian.jira.rest.client.api.domain.BasicProject;
import com.atlassian.jira.rest.client.api.domain.CimFieldInfo;
import com.atlassian.jira.rest.client.api.domain.CimIssueType;
import com.atlassian.jira.rest.client.api.domain.CimProject;
import com.atlassian.jira.rest.client.api.domain.CustomFieldOption;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueType;
import com.atlassian.jira.rest.client.api.domain.Priority;
import com.atlassian.jira.rest.client.api.domain.Project;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import com.atlassian.util.concurrent.Promise;
import com.eviware.soapui.SoapUI;
import com.eviware.soapui.actions.SoapUIPreferencesAction;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.model.ModelItem;
import com.eviware.soapui.model.settings.Settings;
import com.eviware.soapui.model.support.ModelSupport;
import com.eviware.soapui.support.StringUtils;
import com.eviware.soapui.support.UISupport;
import com.smartbear.ready.plugin.jira.factories.JiraPrefsFactory;
import com.smartbear.ready.plugin.jira.settings.BugTrackerPrefs;
import com.smartbear.ready.plugin.jira.settings.BugTrackerSettings;
import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class JiraProvider implements SimpleBugTrackerProvider {
    private static final Logger logger = LoggerFactory.getLogger(JiraProvider.class);

    private final static String BUG_TRACKER_ISSUE_KEY_NOT_SPECIFIED = "No issue key is specified.";
    private final static String BUG_TRACKER_FILE_NAME_NOT_SPECIFIED = "No file name is specified.";
    private final static String BUG_TRACKER_INCORRECT_FILE_PATH = "Incorrect file path.";
    private final static String BUG_TRACKER_URI_IS_INCORRECT = "The JIRA URL format is incorrect.";
    public static final String BUG_TRACKER_SETTINGS_ARE_NOT_COMPLETELY_SPECIFIED = "Unable to create a JIRA item.\nThe JIRA Integration plugin's settings are not configured or invalid.";
    public static final String INCORRECT_PROTOCOL_IN_THE_JIRA_URL = "\nPerhaps,  you specified the HTTP protocol in the JIRA URL instead of HTTPS.";
    public static final String INCORRECT_PROTOCOL_ERROR_CODE = "301";

    private ModelItem activeElement;
    private JiraRestClient restClient = null;
    private BugTrackerSettings bugTrackerSettings;
    static private JiraProvider instance = null;

    //Properties below exist for reducing number of Jira API calls since every call is very greedy operation
    Iterable<BasicProject> allProjects = null;
    Map<String, Project> requestedProjects = new HashMap<>();
    Iterable<Priority> priorities = null;
    Map<String/*project*/,Map<String/*Issue Type*/, Map<String/*FieldName*/, CimFieldInfo>>> projectFields = new HashMap<>();

    public static JiraProvider getProvider (){
        if (instance == null){
            instance = new JiraProvider();
        }
        return instance;
    }

    public static void freeProvider(){
        instance = null;
    }

    private JiraProvider() {
        bugTrackerSettings = getBugTrackerSettings();
        if (!settingsComplete(bugTrackerSettings)) {
            logger.error(BUG_TRACKER_URI_IS_INCORRECT);
            UISupport.showErrorMessage(BUG_TRACKER_SETTINGS_ARE_NOT_COMPLETELY_SPECIFIED);
            showSettingsDialog();
            if (!settingsComplete(bugTrackerSettings)) {
                return;
            }
        }
        final AsynchronousJiraRestClientFactory factory = new AsynchronousJiraRestClientFactory();
        try {
            restClient = factory.createWithBasicHttpAuthentication(new URI(bugTrackerSettings.getUrl()), bugTrackerSettings.getLogin(), bugTrackerSettings.getPassword());
        } catch (URISyntaxException e) {
            logger.error(BUG_TRACKER_URI_IS_INCORRECT);
            UISupport.showErrorMessage(BUG_TRACKER_URI_IS_INCORRECT);
        }
    }

    private void showSettingsDialog() {
        SoapUIPreferencesAction.getInstance().show(JiraPrefsFactory.JIRA_PREFS_TITLE);
        createBugTrackerSettings();
    }

    private JiraApiCallResult<Iterable<BasicProject>> getAllProjects() {
        if (allProjects != null){
            return new JiraApiCallResult<Iterable<BasicProject>>(allProjects);
        }

        try {
            allProjects = restClient.getProjectClient().getAllProjects().get();
            return new JiraApiCallResult<Iterable<BasicProject>>(allProjects);
        } catch (InterruptedException e) {
            logger.error(e.getMessage());
            allProjects = null;
            return new JiraApiCallResult<Iterable<BasicProject>>(e);
        } catch (ExecutionException e) {
            logger.error(e.getMessage());
            allProjects = null;
            return new JiraApiCallResult<Iterable<BasicProject>>(e);
        }
    }

    public List<String> getListOfAllProjects() {
        JiraApiCallResult<Iterable<BasicProject>> projects = getAllProjects();
        if (!projects.isSuccess()) {
            return new ArrayList<String>();
        }

        List<String> projectNames = new ArrayList<String>();
        for (BasicProject project : projects.getResult()) {
            projectNames.add(project.getKey());
        }

        return projectNames;
    }

    private JiraApiCallResult<Project> getProjectByKey(String key) {
        if (!requestedProjects.containsKey(key)) {
            try {
                requestedProjects.put(key, restClient.getProjectClient().getProject(key).get());
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
                return new JiraApiCallResult<Project>(e);
            } catch (ExecutionException e) {
                logger.error(e.getMessage());
                return new JiraApiCallResult<Project>(e);
            }
        }
        return new JiraApiCallResult<Project>(requestedProjects.get(key));
    }

    private JiraApiCallResult<OptionalIterable<IssueType>> getProjectIssueTypes(String projectKey) {
        JiraApiCallResult<Project> project = getProjectByKey(projectKey);
        if (!project.isSuccess()) {
            return new JiraApiCallResult<OptionalIterable<IssueType>>(project.getError());
        }
        return new JiraApiCallResult<OptionalIterable<IssueType>>(project.getResult().getIssueTypes());
    }

    public List<String> getListOfProjectIssueTypes(String projectKey) {
        JiraApiCallResult<OptionalIterable<IssueType>> result = getProjectIssueTypes(projectKey);
        if (!result.isSuccess()) {
            return new ArrayList<String>();
        }

        List<String> issueTypeList = new ArrayList<String>();
        OptionalIterable<IssueType> issueTypes = result.getResult();
        for (IssueType issueType : issueTypes) {
            issueTypeList.add(issueType.getName());
        }

        return issueTypeList;
    }

    public CustomFieldOption transformToCustomFieldOption(Object object){
        if (object instanceof CustomFieldOption){
            return (CustomFieldOption)object;
        }

        return null;
    }

    private JiraApiCallResult<Iterable<Priority>> getAllPriorities() {
        if (priorities == null) {
            final MetadataRestClient client = restClient.getMetadataClient();
            try {
                priorities = client.getPriorities().get();
            } catch (InterruptedException e) {
                return new JiraApiCallResult<Iterable<Priority>>(e);
            } catch (ExecutionException e) {
                return new JiraApiCallResult<Iterable<Priority>>(e);
            }
        }
        return new JiraApiCallResult<Iterable<Priority>>(priorities);
    }

    private Priority getPriorityByName(String priorityName) {
        JiraApiCallResult<Iterable<Priority>> priorities = getAllPriorities();
        if (!priorities.isSuccess()) {
            return null;
        }
        for (Priority priority : priorities.getResult()) {
            if (priority.getName().equals(priorityName)) {
                return priority;
            }
        }
        return null;
    }

    private JiraApiCallResult<IssueType> getIssueType(String projectKey, String requiredIssueType) {
        JiraApiCallResult<OptionalIterable<IssueType>> issueTypes = getProjectIssueTypes(projectKey);
        if (!issueTypes.isSuccess()) {
            return new JiraApiCallResult<IssueType>(issueTypes.getError());
        }
        for (IssueType issueType : issueTypes.getResult()) {
            if (issueType.getName().equals(requiredIssueType)) {
                return new JiraApiCallResult<IssueType>(issueType);
            }
        }
        return null;
    }

    public Issue getIssue(String key) {
        try {
            return restClient.getIssueClient().getIssue(key).get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        return null;
    }

    public Map<String,Map<String, Map<String, CimFieldInfo>>> getProjectFields (String ... projects){
        JiraApiCallResult<Map<String,Map<String, Map<String, CimFieldInfo>>>> projectFieldsResult = getProjectFieldsInternal(projects);
        if (projectFieldsResult.isSuccess()){
            return projectFieldsResult.getResult();
        }

        return null;
    }

    private JiraApiCallResult<Map<String,Map<String, Map<String, CimFieldInfo>>>> getProjectFieldsInternal (String ... projects){
        List<String> unCachedProjectsList = new ArrayList<>();
        for (String project:projects){
            if (!projectFields.containsKey(project)){
                unCachedProjectsList.add(project);
            }
        }
        if (unCachedProjectsList.size() > 0) {
            String [] unCachedProjectsArray = new String[unCachedProjectsList.size()];
            unCachedProjectsList.toArray(unCachedProjectsArray);
            GetCreateIssueMetadataOptions options = new GetCreateIssueMetadataOptionsBuilder()
                    .withExpandedIssueTypesFields()
                    .withProjectKeys(unCachedProjectsList.toArray(unCachedProjectsArray))
                    .build();
            try {
                Iterable<CimProject> cimProjects = restClient.getIssueClient().getCreateIssueMetadata(options).get();
                for (CimProject cimProject : cimProjects) {
                    Iterable<CimIssueType> issueTypes = cimProject.getIssueTypes();
                    HashMap<String, Map<String, CimFieldInfo>> issueTypeFields = new HashMap<String, Map<String, CimFieldInfo>>();
                    for (CimIssueType currentIssueType : issueTypes) {
                        issueTypeFields.put(currentIssueType.getName(), currentIssueType.getFields());
                    }
                    projectFields.put(cimProject.getKey(),issueTypeFields);
                }
            } catch (InterruptedException e) {
                return new JiraApiCallResult<Map<String,Map<String, Map<String, CimFieldInfo>>>>(e);
            } catch (ExecutionException e) {
                return new JiraApiCallResult<Map<String,Map<String, Map<String, CimFieldInfo>>>>(e);
            }
        }
        return new JiraApiCallResult<Map<String,Map<String, Map<String, CimFieldInfo>>>>(projectFields);
    }

    private boolean isCustomFieldOptionValue (String projectKey, String issueTypeKey, String fieldName){
        Map<String,Map<String, Map<String, CimFieldInfo>>> projectFields = getProjectFields(projectKey);
        CimFieldInfo fieldInfo = projectFields.get(projectKey).get(issueTypeKey).get(fieldName);
        Iterable<Object> allowedValues = fieldInfo.getAllowedValues();
        return allowedValues != null;
    }

    @Override
    public IssueCreationResult createIssue(String projectKey, String issueTypeKey, String summary, String description, Map<String, String> extraRequiredValues) {
        //https://bitbucket.org/atlassian/jira-rest-java-client/src/75a64c9d81aad7d8bd9beb11e098148407b13cae/test/src/test/java/samples/Example1.java?at=master
        if (restClient == null) {
            return new IssueCreationResult(BUG_TRACKER_URI_IS_INCORRECT);
        }

        BasicIssue basicIssue = null;
        try {
            JiraApiCallResult<IssueType> issueType = getIssueType(projectKey, issueTypeKey);
            if (!issueType.isSuccess()) {
                return new IssueCreationResult(issueType.getError().getMessage());
            }

            IssueInputBuilder issueInputBuilder = new IssueInputBuilder(projectKey, issueType.getResult().getId());
            issueInputBuilder.setIssueType(issueType.getResult());
            issueInputBuilder.setProjectKey(projectKey);
            issueInputBuilder.setSummary(summary);
            issueInputBuilder.setDescription(description);
            for (final Map.Entry<String, String> extraRequiredValue : extraRequiredValues.entrySet()) {
                if (extraRequiredValue.getKey().equals("priority")) {
                    issueInputBuilder.setPriority(getPriorityByName(extraRequiredValue.getValue()));
                } else if (extraRequiredValue.getKey().equals("components")) {
                    issueInputBuilder.setComponentsNames(new Iterable<String>() {
                        @Override
                        public Iterator<String> iterator() {
                            return new Iterator<String>() {
                                boolean hasValue = true;

                                @Override
                                public boolean hasNext() {
                                    return hasValue;
                                }

                                @Override
                                public String next() {
                                    hasValue = false;
                                    return extraRequiredValue.getValue();
                                }

                                @Override
                                public void remove() {

                                }
                            };
                        }
                    });
                } else if (extraRequiredValue.getKey().equals("versions")){
                    issueInputBuilder.setAffectedVersionsNames(new Iterable<String>() {
                        @Override
                        public Iterator<String> iterator() {
                            return new Iterator<String>() {
                                boolean hasValue = true;
                                @Override
                                public boolean hasNext() {
                                    return hasValue;
                                }

                                @Override
                                public String next() {
                                    hasValue = false;
                                    return extraRequiredValue.getValue();
                                }

                                @Override
                                public void remove() {

                                }
                            };
                        }
                    });
                } else if (extraRequiredValue.getKey().equals("fixVersions")){
                    issueInputBuilder.setFixVersionsNames(new Iterable<String>() {
                        @Override
                        public Iterator<String> iterator() {
                            return new Iterator<String>() {
                                boolean hasValue = true;

                                @Override
                                public boolean hasNext() {
                                    return hasValue;
                                }

                                @Override
                                public String next() {
                                    hasValue = false;
                                    return extraRequiredValue.getValue();
                                }

                                @Override
                                public void remove() {

                                }
                            };
                        }
                    });
                } else if (extraRequiredValue.getKey().equals("assignee")){
                    issueInputBuilder.setAssigneeName(extraRequiredValue.getValue());
                } else if (extraRequiredValue.getKey().equals("parent")){
                    Map<String, Object> parent = new HashMap<String, Object>();
                    parent.put("key", extraRequiredValue.getValue());
                    FieldInput parentField = new FieldInput("parent", new ComplexIssueInputFieldValue(parent));
                    issueInputBuilder.setFieldInput(parentField);
                } else if (extraRequiredValue.getKey().equals("resolution")){
                    Map<String, Object> customOptionValue = new HashMap<>();
                    customOptionValue.put("name", extraRequiredValue.getValue());
                    issueInputBuilder.setFieldValue(extraRequiredValue.getKey(), new ComplexIssueInputFieldValue(customOptionValue));
                } else if (isCustomFieldOptionValue(projectKey, issueTypeKey, extraRequiredValue.getKey())) {
                    Map<String, Object> customOptionValue = new HashMap<>();
                    customOptionValue.put("value", extraRequiredValue.getValue());
                    issueInputBuilder.setFieldValue(extraRequiredValue.getKey(), new ComplexIssueInputFieldValue(customOptionValue));
                } else {
                    issueInputBuilder.setFieldValue(extraRequiredValue.getKey(), extraRequiredValue.getValue());
                }
            }

            Promise<BasicIssue> issue = restClient.getIssueClient().createIssue(issueInputBuilder.build());
            basicIssue = issue.get();
        } catch (InterruptedException e) {
            String errorMessage = e.getMessage();
            if (errorMessage.contains(INCORRECT_PROTOCOL_ERROR_CODE)){
                errorMessage += INCORRECT_PROTOCOL_IN_THE_JIRA_URL;
            }
            return new IssueCreationResult(errorMessage);
        } catch (ExecutionException e) {
            String errorMessage = e.getMessage();
            if (errorMessage.contains(INCORRECT_PROTOCOL_ERROR_CODE)){
                errorMessage += INCORRECT_PROTOCOL_IN_THE_JIRA_URL;
            }
            return new IssueCreationResult(errorMessage);
        }

        return new IssueCreationResult(basicIssue);
    }

    protected void finalize() throws Throwable {
        try {
            if (restClient != null) {
                restClient.close();
            }
        } catch (IOException e) {
        }
    }

    @Override
    public AttachmentAddingResult attachFile(URI attachmentUri, String fileName, InputStream inputStream) {
        if (attachmentUri == null) {
            return new AttachmentAddingResult(BUG_TRACKER_ISSUE_KEY_NOT_SPECIFIED);
        }
        if (StringUtils.isNullOrEmpty(fileName)) {
            return new AttachmentAddingResult(BUG_TRACKER_FILE_NAME_NOT_SPECIFIED);
        }

        try {
            restClient.getIssueClient().addAttachment(attachmentUri, inputStream, fileName).get();
        } catch (InterruptedException e) {
            return new AttachmentAddingResult(e.getMessage());
        } catch (ExecutionException e) {
            return new AttachmentAddingResult(e.getMessage());
        }

        return new AttachmentAddingResult();//everything is ok
    }

    @Override
    public AttachmentAddingResult attachFile(URI attachmentUri, String filePath){
        if (attachmentUri == null) {
            return new AttachmentAddingResult(BUG_TRACKER_ISSUE_KEY_NOT_SPECIFIED);
        }
        if (StringUtils.isNullOrEmpty(filePath)) {
            return new AttachmentAddingResult(BUG_TRACKER_INCORRECT_FILE_PATH);
        }
        File file = new File (filePath);
        if (!file.exists() && file.isFile()){
            return new AttachmentAddingResult(BUG_TRACKER_INCORRECT_FILE_PATH);
        }

        restClient.getIssueClient().addAttachments(attachmentUri, file);
        return new AttachmentAddingResult();
    }

    private InputStream getExecutionLog(String loggerName) {
        org.apache.log4j.Logger loggerr = org.apache.log4j.Logger.getLogger(loggerName);
        FileAppender fileAppender = null;
        Enumeration appenders = loggerr.getRootLogger().getAllAppenders();
        while (appenders.hasMoreElements()){
            Appender currentAppender = (Appender)appenders.nextElement();
            if  (currentAppender instanceof FileAppender){
                fileAppender = (FileAppender)currentAppender;
            }
        }

        if(fileAppender != null){
            try {
                return (InputStream) new FileInputStream(fileAppender.getFile());
            } catch (FileNotFoundException e) {
                JiraProvider.logger.error(e.getMessage());
            }
        }

        return null;
    }

    public InputStream getServiceVExecutionLog() {
        return getExecutionLog("com.smartbear.servicev");
    }

    public InputStream getLoadUIExecutionLog() {
        return getExecutionLog("com.eviware.loadui");
    }

    public InputStream getReadyApiLog() {
        return getExecutionLog("com.smartbear.ready");
    }

    public void setActiveItem(ModelItem element) {
        activeElement = element;
    }

    public String getActiveItemName() {
        return activeElement.getName();
    }

    public String getRootProjectName() {
        WsdlProject project = findActiveElementRootProject(activeElement);
        return project.getName();
    }

    public InputStream getRootProject() {
        WsdlProject project = findActiveElementRootProject(activeElement);
        return new ByteArrayInputStream(project.getConfig().toString().getBytes(StandardCharsets.UTF_8));
    }

    private WsdlProject findActiveElementRootProject(ModelItem activeElement) {
        return ModelSupport.getModelItemProject(activeElement);
    }

    public boolean settingsComplete(BugTrackerSettings settings) {
        return !(settings == null ||
                StringUtils.isNullOrEmpty(settings.getUrl()) ||
                StringUtils.isNullOrEmpty(settings.getLogin()) ||
                StringUtils.isNullOrEmpty(settings.getPassword()));
    }

    public boolean settingsComplete() {
        BugTrackerSettings settings = getBugTrackerSettings();
        return settingsComplete(settings);
    }

    public BugTrackerSettings getBugTrackerSettings() {
        if (bugTrackerSettings == null) {
            createBugTrackerSettings();
        }
        return bugTrackerSettings;
    }

    private void createBugTrackerSettings() {
        Settings soapuiSettings = SoapUI.getSettings();
        bugTrackerSettings = new BugTrackerSettings(soapuiSettings.getString(BugTrackerPrefs.DEFAULT_URL, ""),
                soapuiSettings.getString(BugTrackerPrefs.LOGIN, ""),
                soapuiSettings.getString(BugTrackerPrefs.PASSWORD, ""));
    }
}