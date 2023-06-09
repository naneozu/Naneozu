package nnz.userservice.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.eello.nnz.common.dto.PageDTO;
import io.github.eello.nnz.common.exception.CustomException;
import io.github.eello.nnz.common.jwt.DecodedToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nnz.userservice.dto.*;
import nnz.userservice.exception.ErrorCode;
import nnz.userservice.service.*;
import nnz.userservice.util.CookieUtils;
import nnz.userservice.util.ValidationUtils;
import nnz.userservice.vo.FindPwdVO;
import nnz.userservice.vo.LoginVO;
import nnz.userservice.vo.UserJoinVO;
import nnz.userservice.vo.UserUpdateProfileVO;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final VerifyService verifyService;
    private final FollowService followService;
    private final BookmarkService bookmarkService;
    private final AskService askService;

    @PostMapping("/users/join")
    public ResponseEntity<Void> join(@RequestBody UserJoinVO vo) throws UnsupportedEncodingException, JsonProcessingException {
        userService.join(vo);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/check")
    public ResponseEntity<Map<String, Boolean>> duplicateCheck(
            @RequestParam("type") String type,
            @RequestParam("val") String val) throws UnsupportedEncodingException {
        boolean available = false;
        if ("email".equals(type)) {
            available = ValidationUtils.isValidEmail(val) && !userService.isExistByEmail(val);
        } else if ("nickname".equals(type)) {
            available = ValidationUtils.isValidNickname(val) && !userService.isExistByNickname(val);
        }

        Map<String, Boolean> response = new HashMap<>();
        response.put("available", available); // 존재하면 사용 불가능이므로 not 연산하여 응답
        return ResponseEntity.ok(response);
    }

    @PostMapping("/users/login")
    public ResponseEntity<TokenDTO> login(@RequestBody LoginVO vo, HttpServletResponse response) throws JsonProcessingException {
        TokenDTO token = userService.login(vo);

//        ResponseCookie cookie = ResponseCookie.from("refresh", token.getRefreshToken())
//                .httpOnly(true)
//                .path("/")
//                .build();
//
//        response.addHeader("Set-Cookie", cookie.toString());
//        token.deleteRefreshToken(); // 응답에 refreshToken을 포함시키지 않기 위해 null로 변경
        return ResponseEntity.ok(token);
    }

    @PostMapping("/users/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, DecodedToken token) {
        String accessToken = request.getHeader("Authorization").substring(7);
        userService.logout(accessToken, token);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/follow/{followingId}")
    public ResponseEntity<Map<String, Boolean>> follow(@PathVariable Long followingId, DecodedToken token) throws JsonProcessingException {
        // 팔로우가 되어 있으면 언팔로우, 언팔로우면 팔로우
        boolean follow = followService.toggleFollow(token.getId(), followingId);
        Map<String, Boolean> response = new HashMap<>();
        response.put("follow", follow);

        return ResponseEntity.ok(response);
    }

//    @PostMapping("/users/follow/{followingId}")
//    public ResponseEntity<Void> follow(@PathVariable Long followingId, DecodedToken token) {
//        // 요청자(token.getId()) 가 followingId에 해당하는 사용자를 팔로우
//        followService.follow(token.getId(), followingId);
//        return ResponseEntity.noContent().build();
//    }

//    @PostMapping("/users/unfollow/{followingId}")
//    public ResponseEntity<Void> unfollow(@PathVariable Long followingId, DecodedToken token) {
//        // 요청자(token.getId()) 가 followingId에 해당하는 사용자를 언팔로우
//        followService.unfollow(token.getId(), followingId);
//        return ResponseEntity.noContent().build();
//    }

    @PatchMapping("/users/find-pwd")
    public ResponseEntity<Void> findPwd(@RequestBody FindPwdVO vo) {
        userService.findPwd(vo);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/bookmarks/{nanumId}")
    public ResponseEntity<Map<String, Boolean>> toggleWish(@PathVariable Long nanumId, DecodedToken token) throws JsonProcessingException {
        boolean wish = bookmarkService.toggleWish(token.getId(), nanumId);

        Map<String, Boolean> response = new HashMap<>();
        response.put("bookmark", wish);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/users/bookmarks")
    public ResponseEntity<List<BookmarkedNanumDTO>> findBookmarkedNanum(DecodedToken token) {
        List<BookmarkedNanumDTO> bookmarkedNanum = userService.findBookmarkedNanum(token.getId());
        return ResponseEntity.ok(bookmarkedNanum);
    }

    @GetMapping("/users")
    public ResponseEntity<UserDTO> info(DecodedToken token) {
        UserDTO info = userService.info(token.getId());
        return ResponseEntity.ok(info);
    }

    @GetMapping("/users/{otherUserId}")
    public ResponseEntity<OtherUserInfoDTO> otherUserInfo(DecodedToken token, @PathVariable Long otherUserId) {
        OtherUserInfoDTO otherUserInfo =
                userService.otherUserInfo(token == null ? null : token.getId(), otherUserId);
        return ResponseEntity.ok(otherUserInfo);
    }

    @GetMapping("/users/nanums")
    public ResponseEntity<PageDTO> getNanums(DecodedToken token, @RequestParam("type") String type, Pageable pageable) {
        Long userId = token.getId();

        PageDTO pageDTO;
        if ("receive".equals(type)) {
            pageDTO = userService.receivedNanums(userId, pageable);
        } else pageDTO = userService.providedNanums(userId, pageable);

        return ResponseEntity.ok(pageDTO);
    }

    @GetMapping("/users/nanums/{nanumId}")
    public ResponseEntity<NanumParticipantsDTO> getNanumParticipants(@PathVariable Long nanumId, DecodedToken token) {
        NanumParticipantsDTO nanumParticipants = userService.nanumParticipants(token.getId(), nanumId);
        return ResponseEntity.ok(nanumParticipants);
    }

    @PatchMapping("/users")
    public ResponseEntity<Void> updateUser(
            DecodedToken token,
            @RequestPart("data") UserUpdateProfileVO vo,
            @RequestParam(value = "file", required = false) MultipartFile file) throws UnsupportedEncodingException, JsonProcessingException {
        vo.decodeNickname();
        log.info("input nickname: {}", vo.getNickname());
        userService.updateProfile(token.getId(), vo, file);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/ask/show")
    public ResponseEntity<Void> askRegisterShow(DecodedToken token, @RequestBody ShowRegisterVO vo) throws JsonProcessingException {
        vo.setRequester(token.getId());
        askService.askRegisterShow(vo);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/report")
    public ResponseEntity<Void> reportUser(
            DecodedToken token,
            @RequestBody UserReportVO vo) throws JsonProcessingException {
        vo.setReporterId(token.getId());
        askService.reportUser(vo);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/reissue")
    public ResponseEntity<Map<String, String>> reissue(DecodedToken token, HttpServletRequest request) {
        Cookie cookie = CookieUtils.getCookieByName(request, "refreshToken");

        if (cookie == null) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_NOT_IN_COOKIE);
        }

        TokenDTO tokenDTO = TokenDTO.builder().refreshToken(cookie.getValue())
                .userId(token.getId())
                .build();

        String reissuedAccessToken = userService.reissue(tokenDTO);

        Map<String, String> response = new HashMap<>();
        response.put("accessToken", reissuedAccessToken);
        return ResponseEntity.ok(response);
    }
}