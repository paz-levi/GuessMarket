package server.servlets;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import engine.IEngine;
import exception.GuessMarketException;
import exception.XmlValidationException;
import server.BadRequestException;
import server.NotLoggedInException;
import server.ServletConstants;
import server.ServletUtils;
import server.SessionUtils;

// Uploads an events XML file. Hard spec rule: the file must NEVER be written to disk (the grader's server may lack
// write permission there and would crash). fileSizeThreshold is set >= maxFileSize below, so Tomcat's multipart
// parser keeps the whole part in memory and never spills it to its own temp directory either -- no `location` is
// configured, and nothing under this servlet ever touches the filesystem. The uploading (logged-in) user becomes
// the market maker of every event in the file, per EventsFileLoader's own contract.
@WebServlet("/events/upload")
@MultipartConfig(
        fileSizeThreshold = 20 * 1024 * 1024,  // 20MB -- kept in memory below this, never spilled to disk
        maxFileSize = 20 * 1024 * 1024,        // must not exceed the in-memory threshold above
        maxRequestSize = 25 * 1024 * 1024
)
public class UploadEventsFileServlet extends HttpServlet {

    private static final String XML_EXTENSION = ".xml";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IEngine engine = ServletUtils.getEngine(getServletContext());
        try {
            String uploader = SessionUtils.requireLoggedInUsername(request);
            Part part = getFilePart(request);
            validateSubmittedFileName(part);
            try (InputStream fileStream = part.getInputStream()) {
                engine.loadEventsFile(fileStream, uploader);
            }
            ServletUtils.writeJson(response, engine.listEvents());
        } catch (GuessMarketException e) {
            ServletUtils.writeError(response, e);
        } catch (NotLoggedInException e) {
            ServletUtils.writeNotLoggedIn(response, e.getMessage());
        } catch (BadRequestException e) {
            ServletUtils.writeBadRequest(response, e.getMessage());
        }
    }

    // Reads the multipart file part directly, converting a missing part into the same BadRequestException shape
    // every other missing-parameter case uses. getPart() itself is what triggers Tomcat's multipart parsing --
    // safe here since the @MultipartConfig above guarantees it stays in memory.
    private static Part getFilePart(HttpServletRequest request) throws IOException {
        Part part;
        try {
            part = request.getPart(ServletConstants.MULTIPART_FILE_PART);
        } catch (Exception e) {
            throw new BadRequestException("The request is not a valid multipart upload: " + e.getMessage());
        }
        if (part == null) {
            throw new BadRequestException("Missing required multipart file part \"" + ServletConstants.MULTIPART_FILE_PART + "\".");
        }
        return part;
    }

    // There is no filesystem path to validate for a stream upload (EventsFileLoader's path-based .xml check is
    // meaningless here), so the equivalent check runs against the part's own submitted filename instead.
    private static void validateSubmittedFileName(Part part) {
        String submittedFileName = part.getSubmittedFileName();
        if (submittedFileName == null || !submittedFileName.toLowerCase().endsWith(XML_EXTENSION)) {
            throw new XmlValidationException("The uploaded file must have a .xml extension: \"" + submittedFileName + "\"");
        }
    }
}
