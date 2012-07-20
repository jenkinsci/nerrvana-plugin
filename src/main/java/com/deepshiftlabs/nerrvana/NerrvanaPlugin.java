package com.deepshiftlabs.nerrvana;

import hudson.*;
import hudson.util.*;
import hudson.model.*;
import hudson.tasks.*;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.*;
import java.util.*;

/**
 * NerrvanaPlugin - remotely runs Selenium tests at Nerrvana cloud.<br> 
 * For details see {@link <a href="https://nerrvana.com/docs/get-started">Nerrvana - GET STARTED</a>}<br>
 * <br>
 * How plugin works:
 * <ol>
 * <li>Uploads Selenium tests to the Nerrvana cloud by FTPS.
 * <li>Starts tests using Nerrvana API. 
 * <li>Polls Nerrvana for tests execution status until they're complete. 
 * <li>Creates report with tests result. Report contains link to the test artifacts
 * on Nerrvana side.
 * </ol>
 * NOTE: plugin uses {@link <a href="http://lftp.yar.ru/desc.html">lftp application</a>} to upload tests to the Nerrvana cloud.
 * 
 * @author <a href="http://www.deepshiftlabs.com/">Deep Shift Labs</a>
 * @author <a href="mailto:wise@deepshiftlabs.com">Victor Orlov</a>
 * @version 1.00
 */
public class NerrvanaPlugin extends Builder {
    /**
     * <p>Plugin configuration parameter 'settingsXmlString'. This parameter is
     * represented in config.jelly as TEXTAREA</p>
     * <br>
     * <p>Nerrvana user should generate settings XML in Nerrvana web interface and paste
     * it into the plugin config field "Nerrvana plugin settings"</p>
     * <br>
     * Parameter is initialized in plugin constructor.
     */
    public final String settingsXmlString;
    
    /**
     * <p>Plugin configuration parameter 'logLevel'. This parameter is represented
     * in config.jelly as SELECT with two values (NORMAL and TRACE)</p>
     * <br>
     * Parameter is initialized in plugin constructor
     */
    public final String loglevel;

    /**
     * Nerrvana plugin settings object
     */
    private NerrvanaPluginSettings settings;

    /**
     * <p>Template of the command file for {@link <a href="http://lftp.yar.ru/desc.html">lftp application</a>}.
     * This template is used if Jenkins <b>puts tests into some folder inside job's workspace</b>.</p>
     * <br>
     * Plugin creates actual command set using following parameters from {@link NerrvanaPluginSettings}:
     * <ol>
     * <li>1 - ftp host {@link NerrvanaPluginSettings#ftpurl} 
     * <li>2 - user {@link NerrvanaPluginSettings#ftpuser}
     * <li>3 - pass {@link NerrvanaPluginSettings#ftppass}
     * <li>4 - ftp folder {@link NerrvanaPluginSettings#space_path}
     * <li>5 - local folder (in Jenkins job workspace) {@link NerrvanaPluginSettings#folder_with_tests}
     * </ol>
     */
    private static final String ftpTemplateWithLocalFolder = 
    "open %s -u %s,%s\n" + 
    "set ftp:list-options -a\n" + 
    "set ftp:ssl-protect-data on\n" + 
    "cd %s\n" + 
    "lcd %s\n" + 
    "mirror --delete --parallel=50 -R .\n" + 
    "";

    /**
     * <p>Template of the command file for {@link <a href="http://lftp.yar.ru/desc.html">lftp application</a>} 
     * This template is used if Jenkins <b>puts tests directly into workspace</b>.</p>
     * <br>
     * Plugin creates actual command set using following parameters from {@link NerrvanaPluginSettings}:
     * <ol>
     * <li>1 - ftp host {@link NerrvanaPluginSettings#ftpurl} 
     * <li>2 - user {@link NerrvanaPluginSettings#ftpuser}
     * <li>3 - pass {@link NerrvanaPluginSettings#ftppass}
     * <li>4 - ftp folder {@link NerrvanaPluginSettings#space_path}
     * </ol>
     */
    private static final String ftpTemplateNoFolder = 
    "open %s -u %s,%s\n" + 
    "set ftp:list-options -a\n" + 
    "set ftp:ssl-protect-data on\n" + 
    "cd %s\n" + 
    "mirror --delete --parallel=50 -R .\n" +
    "glob -a rm -r upload-build*\n" + 
    "";

    /**
     * Plugin constructor<br>
     * Fields in config.jelly must match the parameter names in the org.kohsuke.stapler.DataBoundConstructor
     * 
     * @param settingsXmlString Plugin settings generated by {@link <a href="https://cloud.nerrvana.com">Nerrvana</a>}}
     * @param loglevel Log level for plugin operations
     */
    @DataBoundConstructor
    public NerrvanaPlugin(String settingsXmlString, String loglevel) {
        this.settingsXmlString = settingsXmlString;
        this.loglevel = loglevel;
    }

    /**
     * Creates {@link ReportAction} object and adds it to the list of build actions
     * @param build current build
     * @param space_name {@link NerrvanaPluginSettings#space_name}
     * @param space_path {@link NerrvanaPluginSettings#space_path}
     * @param testrun Nerrvana testrun
     * @param exec Nerrvana execution object
     * @throws Exception
     */
    private void saveNerrvanaReport(AbstractBuild<?, ?> build, String space_name, String space_path, Testrun testrun, NerrvanaExecution exec) throws Exception {
        ReportAction action = new ReportAction(build, space_name, space_path, testrun, exec);
        build.getActions().add(action);
    }

    /**
     * Merges several environment variables into one set
     * So far it merges
     * - build.getEnvironment(listener); 
     * - build.getBuildVariables();
     *  
     * TODO: consider adding 
     * - Hudson.getInstance().getGlobalNodeProperties().get(EnvironmentVariablesNodeProperty.class);
     * - Computer.currentComputer().getEnvironment();
     * 
     * @param build 
     * @param listener
     * @return merged set of variables
     * @throws Exception
     */
    private EnvVars getEnvironment(AbstractBuild<?, ?> build, BuildListener listener) throws Exception {
        StringBuilder sb = new StringBuilder();
        EnvVars envVars = build.getEnvironment(listener);
        for (Map.Entry<String, String> e : build.getBuildVariables().entrySet()) {
            envVars.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : envVars.entrySet()) {
            sb.append(e.getKey() + " = " + e.getValue() + "\n");
        }

        if (sb.length() > 0) {
            Logger.infoln("---BEGIN BUILD ENVIRONMENT---\n");
            Logger.info(sb.toString());
            Logger.infoln("-----END BUILD ENVIRONMENT---\n");
        }
        return envVars;
    }

    /**
     * Creates command script for {@link <a href="http://lftp.yar.ru/desc.html">lftp application</a>} and
     * uploads tests to the Nerrvana.
     *   
     * @param build
     * @param launcher
     * @param listener
     * @return true on successful completion
     * @throws Exception
     */
    private boolean uploadTests(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws Exception {
        FilePath ws = build.getWorkspace();
        EnvVars envVars = getEnvironment(build, listener);
        String scriptContent = null;
        if (settings.folder_with_tests == null || settings.folder_with_tests.length() == 0)
            scriptContent = String.format(ftpTemplateNoFolder, settings.ftpurl, settings.ftpuser, settings.ftppass, settings.space_path);
        else {
            if (ws.child(settings.folder_with_tests).exists()) {
                scriptContent = String.format(ftpTemplateWithLocalFolder, settings.ftpurl, settings.ftpuser, settings.ftppass, settings.space_path, settings.folder_with_tests);
            } else {
                Logger.infoln("Folder '" + settings.folder_with_tests + "' to pick tests from not found (Check configuration parameter 'Workspace folder')");
                return false;
            }
        }
        Logger.traceln("---BEGIN FTP UPLOAD SCRIPT---");
        Logger.trace(scriptContent);
        Logger.traceln("-----END FTP UPLOAD SCRIPT---");
        FilePath script = ws.createTextTempFile("upload-build-" + build.getNumber() + "-", ".ftp", scriptContent, true);
        String[] cmd = new String[] { "lftp", "-f", script.getName() };
        try {
            return launcher.launch().cmds(cmd).envs(envVars).stdout(listener).pwd(ws).join() == 0;
        } finally {
            script.delete();
        }
    }

    /**
     * Main plugin method
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        Logger.init(listener.getLogger(), this.loglevel);

        HttpCommunicator comm = null;
        Testrun testrun = null;
        NerrvanaExecution exec = null;
        // I. initialize plugin
        try {
            // 0. parse Nerrvana Settings
            settings = NerrvanaPluginSettings.parse(Utils.string2xml(settingsXmlString));
            if (!settings.checkSettings())
                return false;

            // 1. generate Nerrvana test run name
            String tname = Testrun.assembleName(settings.test_run_name, build);
            Logger.infoln("Generated test run name: " + tname);

            // 2. create communicator which will talk to Nerrvana cloud
            Logger.trace("Creating HttpCommunicator...");
            comm = new HttpCommunicator(settings);
            Logger.traceln("done.");

            // 3. upload tests to Nerrvana FTP 
            Logger.infoln("---BEGIN UPLOADING TESTS TO NERRVANA FTP---");
            if (!uploadTests(build, launcher, listener)) {
                Logger.infoln("---FTP UPLOAD FAILED");
                return false;
            }
            Logger.infoln("---FTP UPLOAD COMPLETE");

            // 4. Call Nerrvana for test run creation and start 
            Logger.info("Creating and starting test run via Nerrvana HTTP API call...");
            testrun = comm.createTestrun(settings.space_id, tname, settings.test_run_descr, settings.platforms, settings.executable_file, false, settings.nodes_count);
            Logger.infoln("done.\n");
            Logger.infoln("New test run ID#" + testrun.id + ".");
            Logger.infoln("New execution ID#" + testrun.exec_id + ".");
        } catch (Exception e) {
            Logger.exception(e);
            Logger.infoln("Failed starting Nerrvana test run.");
            return false;
        }

        // II. init Nerrvana execution object
        exec = new NerrvanaExecution();
        exec.id = testrun.exec_id;
        long lmaxtime = settings.getMaxtimeMillis();
        long lpoll = settings.getPollMillis();
        
        // III. Poll Nerrvana test run execution status until timelimit exceeded
        //      or Nerrvana responds with 'err' or 'ok' status (which means that work is complete).
        //      Then create report
        try {
            Logger.infoln("---BEGIN NERRVANA POLLING CYCLE (waiting for tests to complete)");
            while (lmaxtime > 0) {
                Thread.sleep(lpoll);
                lmaxtime -= lpoll;
                NerrvanaExecution newExec = comm.getExecutionStatus(exec);
                exec = newExec;
                Logger.tori(exec.toString(), "\tCurrent execution status: " + exec.status + "\n");
                if (!(exec.status == null || exec.status.trim().length() == 0 || exec.status.indexOf("plan") >= 0 || exec.status.indexOf("run") >= 0))
                    break;
            }
            Logger.infoln("-----END NERRVANA POLLING CYCLE---");

            if (lmaxtime <= 0 && exec.status.indexOf("run") >= 0) {
                throw new Exception("Maximum Nervana test execution time exceeded (Consider increasing corresponding parameter in job config).\n");
            }
            Logger.trace("Saving Nerrvana tests execution report...");
            saveNerrvanaReport(build, settings.space_name, settings.space_path, testrun, exec);
            Logger.traceln("done.");
            if (exec.status.equalsIgnoreCase("ok"))
                return true;
            else
                return false;
        } catch (Exception e) {
            Logger.exception(e);
            Logger.traceln("Saving Nerrvana tests failure report...");
            try {
                exec.status = Utils.getTraceAsString(e);
                List<Platform> platforms = exec.getPlatforms();
                for (int i = 0; i < platforms.size(); i++) {
                    platforms.get(i).status = "failure";
                    platforms.get(i).browse_url = "Not available";
                }
                saveNerrvanaReport(build, settings.space_name, settings.space_path, testrun, exec);
                Logger.traceln("done.");
            } catch (Exception f) {
                Logger.traceln("failed.");
                Logger.exception(f);
            }
            return false;
        }
    }

    /**
     * <p>Some Jenkins-specific mumbo-jumbo we ignore:</p>
     * <tt>Overridden for better type safety.
     * If your plugin doesn't really define any property on Descriptor, you don't have to do this.</tt>
     */
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link NerrvanaPlugin}. Used as a singleton. The class
     * is marked as public so that it can be accessed from views.
     * 
     * <p>
     * See
     * <tt>src\main\resources\com\deepshiftlabs\nerrvana\NerrvanaPlugin\config.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckSettingsXmlString(@QueryParameter String value) throws IOException, ServletException {
            try {
                NerrvanaPluginSettings.parse(Utils.string2xml(value));
                return FormValidation.ok();
            } catch (Exception e) {
                return FormValidation.error("Cannot parse XML configuration");
            }
        }

        public FormValidation doCheckLoglevel(@QueryParameter String value) throws IOException, ServletException {
            if (value != null && (value.equals("normal") || value.equals("trace")))
                return FormValidation.ok();
            return FormValidation.error("Unsupported log level: " + value);
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project
            // types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Nerrvana plug-in";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            // ^Can also use req.bindJSON(this, formData);
            // (easier when there are many fields; need set* methods for this,
            // like
            // setUseFrench)
            save();
            return super.configure(req, formData);
        }
    }
}
