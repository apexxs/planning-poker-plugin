package com.redhat.engineering.plugins.actions;

import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.datetime.DateTimeFormatter;
import com.atlassian.jira.datetime.DateTimeStyle;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.mail.Email;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.mail.queue.SingleMailQueueItem;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.google.common.collect.Maps;
import com.redhat.engineering.plugins.domain.Session;
import com.redhat.engineering.plugins.domain.Status;
import com.redhat.engineering.plugins.exceptions.UserNotFoundException;
import com.redhat.engineering.plugins.services.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author vdedik@redhat.com
 */
public class SessionAction extends AbstractAction {
    private static final Logger log = LoggerFactory.getLogger(SessionAction.class);

    private final IssueService issueService;
    private final JiraAuthenticationContext authContext;
    private final SessionService sessionService;
    private final DateTimeFormatter dateTimeFormatter;
    private final PermissionManager permissionManager;
    private final TemplateRenderer templateRenderer;

    // properties
    private String key;
    private String start;
    private String end;
    private String notifyUserList;
    private Boolean edit = false;

    public SessionAction(IssueService issueService, JiraAuthenticationContext authContext,
                         SessionService sessionService, DateTimeFormatter dateTimeFormatter,
                         PermissionManager permissionManager, TemplateRenderer templateRenderer) {
        this.issueService = issueService;
        this.authContext = authContext;
        this.sessionService = sessionService;
        this.dateTimeFormatter = dateTimeFormatter.forLoggedInUser();
        this.permissionManager = permissionManager;
        this.templateRenderer = templateRenderer;
    }

    public Boolean getEdit() {
        return this.edit;
    }

    public void setEdit(Boolean edit) {
        this.edit = edit;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = start;
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = end;
    }

    public String getNotifyUserList() {
        return notifyUserList;
    }

    public void setNotifyUserList(String notifyUserList) {
        this.notifyUserList = notifyUserList;
    }

    private List<ApplicationUser> parsedNotifyUserList;

    public List<ApplicationUser> parseNotifyUserList() {
        if (parsedNotifyUserList != null) {
            return parsedNotifyUserList;
        }

        List<ApplicationUser> result = new ArrayList<ApplicationUser>();
        if (!"".equals(getNotifyUserList())) {
            String[] rawResult = getNotifyUserList().split(",");
            for (String rawUser : rawResult) {
                ApplicationUser user = getUserManager().getUserByName(rawUser.trim());
                if (user == null) {
                    throw new UserNotFoundException(rawUser);
                }
                result.add(user);
            }
        }

        parsedNotifyUserList = result;
        return result;
    }

    @Override
    public String doDefault() throws Exception {

        if (!authContext.isLoggedInUser()) {
            addErrorMessage("You must be logged in to be able to create new session.");
            return ERROR;
        }

        Issue issue = getIssueObject();
        if (issue == null) {
            return ERROR;
        }

        Session session = sessionService.get(issue.getKey());
        if (session != null) {
            if (sessionService.getStatus(session) != Status.FINISHED) {
                addErrorMessage("There already is a created session that hasn't finished yet. " +
                        "Ask the owner to delete it or wait until current session ends.");
                return ERROR;
            }

            addMessage("There is already a poker session created. " +
                    "Creating a new session will delete the old one with all its data (votes).");
        }

        return INPUT;
    }

    @Override
    public void doValidation() {
        DateTimeFormatter dateTimeFormatter = this.dateTimeFormatter.withStyle(DateTimeStyle.COMPLETE);

        Date startParsed = null;
        Date endParsed = null;

        if (getStart() == null || "".equals(getStart())) {
            this.addError("start", "Start date is required.");
        } else {
            try {
                startParsed = dateTimeFormatter.parse(getStart());
                Long fiveMin = 5*60*1000L;
                if (!this.edit && startParsed.getTime() < System.currentTimeMillis() - fiveMin) {
                    this.addError("start", "Start date must be in the future or present.");
                }
            } catch (IllegalArgumentException e) {
                this.addError("start", "Invalid date format.");
            }
        }

        if (getEnd() == null || "".equals(getEnd())) {
            this.addError("end", "End date is required.");
        } else if (startParsed != null) {
            try {
                endParsed = dateTimeFormatter.parse(getEnd());
                if (endParsed.getTime() < startParsed.getTime()) {
                    this.addError("end", "End date must be after start date.");
                }
            } catch (IllegalArgumentException e) {
                this.addError("end", "Invalid date format.");
            }
        }

        try {
            parseNotifyUserList();
        } catch (UserNotFoundException e) {
            this.addError("notifyUserList", "User '" + e.getUserName() + "' not found.");
        }
    }

    @Override
    public String doExecute() throws Exception {
        if (!permissionManager.hasPermission(Permissions.EDIT_ISSUE, getIssueObject(), getCurrentUser())) {
            addErrorMessage("You don't have permission to edit issues.");
            return ERROR;
        }

        if (this.edit) {
            return saveEdit();
        } else {
            return saveCreate();
        }
    }

    public String saveCreate() throws Exception {
        DateTimeFormatter dateTimeFormatter = this.dateTimeFormatter.withStyle(DateTimeStyle.COMPLETE);
        Session session = new Session();
        session.setAuthor(getCurrentUser());
        session.setCreated(new Date());
        session.setIssue(getIssueObject());
        session.setStart(dateTimeFormatter.parse(getStart()));
        session.setEnd(dateTimeFormatter.parse(getEnd()));
        sessionService.save(session);

        sendEmailToNotifyUsers();

        this.addMessage("New Session has been successfully created.");
        return SUCCESS;
    }

    public String saveEdit() throws Exception {
        DateTimeFormatter dateTimeFormatter = this.dateTimeFormatter.withStyle(DateTimeStyle.COMPLETE);
        Session session = getSessionObject();
        session.setStart(dateTimeFormatter.parse(getStart()));
        session.setEnd(dateTimeFormatter.parse(getEnd()));
        sessionService.update(session);

        sendEmailToNotifyUsers();

        this.addMessage("Session has been successfully updated.");
        return SUCCESS;
    }

    public void sendEmailToNotifyUsers() throws Exception {
        for (ApplicationUser user : parseNotifyUserList()) {
            Email em = new Email(user.getEmailAddress());
            em.setSubject("New planning poker session has been created for issue " + getIssueObject().getKey() + ".");

            StringWriter body = new StringWriter();
            Map<String, Object> context = Maps.newHashMap();
            context.put("issue", getIssueObject());
            String baseUrl = getHttpRequest().getRequestURL().toString()
                    .replaceAll(getHttpRequest().getServletPath(), "");
            context.put("baseUrl", baseUrl);
            templateRenderer.render("views/emails/notify.vm", context, body);

            em.setBody(body.toString());
            em.setMimeType("text/html");
            SingleMailQueueItem smqi = new SingleMailQueueItem(em);

            ComponentAccessor.getMailQueue().addItem(smqi);
        }
    }

    public String doDelete() throws Exception {
        Session session = getSessionObject();
        if (session == null) {
            this.addErrorMessage("This session does not exist.");
            return ERROR;
        }
        if (!session.getAuthor().equals(authContext.getUser())) {
            this.addErrorMessage("You must be the owner of the session to be able to delete it.");
            return ERROR;
        }

        sessionService.delete(session);
        this.addMessage("Session for issue " + getKey() + " has been successfully deleted.");

        return SUCCESS;
    }

    public String doEdit() throws Exception {
        Session session = getSessionObject();
        this.edit = true;
        if (!session.getAuthor().equals(authContext.getUser())) {
            return ERROR;
        }

        this.start = this.dateTimeFormatter.withStyle(DateTimeStyle.DATE_TIME_PICKER).format(session.getStart());
        this.end = this.dateTimeFormatter.withStyle(DateTimeStyle.DATE_TIME_PICKER).format(session.getEnd());

        return INPUT;
    }

    private Session currentSession;

    private Session getSessionObject() {
        if (currentSession == null) {
            currentSession = sessionService.get(getKey());
        }
        return currentSession;
    }

    private Issue getIssueObject() {
        IssueService.IssueResult issueResult = issueService.getIssue(getCurrentUser(), getKey());
        if (!issueResult.isValid()) {
            this.addErrorCollection(issueResult.getErrorCollection());
            return null;
        }

        return issueResult.getIssue();
    }

    private ApplicationUser getCurrentUser() {
        return authContext.getLoggedInUser();
    }
}
